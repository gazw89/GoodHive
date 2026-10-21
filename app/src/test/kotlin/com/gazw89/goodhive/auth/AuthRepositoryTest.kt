package com.gazw89.goodhive.auth

import android.util.Log
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.mockito.MockedStatic
import org.mockito.Mockito

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    private lateinit var logging: MockedStatic<Log>
    @Before fun silenceAndroidLogging() { logging = Mockito.mockStatic(Log::class.java) }
    @After fun closeAndroidLogging() { logging.close() }
    private val client: CognitoSrpClient = Mockito.mock(CognitoSrpClient::class.java)
    private val store: TokenStore = Mockito.mock(TokenStore::class.java)
    private val credentials: CredentialStore = Mockito.mock(CredentialStore::class.java)
    private val auth = AuthRepository(client, store, credentials)
    private val config = HiveSsoConfig("pool", "client", "eu-west-1")
    private val tokens = CognitoTokens("new-id", "new-access", "", 3600)

    @Test fun concurrentRejectionsOfSameTokenCauseOneRefresh() = runTest {
        var id = "old-id"
        Mockito.`when`(store.idToken()).thenAnswer { id }
        Mockito.`when`(store.refreshToken()).thenReturn("refresh")
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.refreshTokens(config, "refresh", null)).thenReturn(tokens)
        Mockito.doAnswer { id = (it.arguments[0] as CognitoTokens).idToken; null }
            .`when`(store).save(Mockito.any(CognitoTokens::class.java) ?: tokens, Mockito.anyString())
        val results = List(6) { async { auth.refreshAfterRejection("old-id") } }.awaitAll()
        assertEquals(List(6) { "new-id" }, results)
        Mockito.verify(client).refreshTokens(config, "refresh", null)
        Mockito.verify(store).save(tokens.copy(refreshToken = "refresh"), "refresh")
    }

    @Test fun validCachedTokenDoesNotDiscoverOrRefresh() = runTest {
        Mockito.`when`(store.idToken()).thenReturn("cached-id")
        Mockito.`when`(store.shouldRefresh(0.9)).thenReturn(false)
        assertEquals("cached-id", auth.currentIdToken())
        Mockito.verifyNoInteractions(client)
    }

    @Test fun transientRefreshFailurePreservesSessionAndPropagates() = runTest {
        Mockito.`when`(store.idToken()).thenReturn("old-id")
        Mockito.`when`(store.refreshToken()).thenReturn("refresh")
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.refreshTokens(config, "refresh", null)).thenAnswer { throw IOException("offline") }
        assertFailsWith<IOException> { auth.refreshAfterRejection("old-id") }
        Mockito.verify(store, Mockito.never()).clear(Mockito.anyString())
        Mockito.verify(store, Mockito.never()).clearAccessTokens(Mockito.anyString())
    }
    @Test fun signOutDuringRefreshCannotRestoreTokens() = runTest {
        val subject = AuthRepository(client, store, credentials, {}, backgroundScope)
        Mockito.`when`(store.idToken()).thenReturn("old-id")
        Mockito.`when`(store.refreshToken()).thenReturn("refresh")
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.refreshTokens(config, "refresh", null)).thenAnswer {
            subject.signOut()
            tokens
        }
        assertFailsWith<CancellationException> { subject.refreshAfterRejection("old-id") }
        Mockito.verify(store).clear("sign_out")
        Mockito.verify(store, Mockito.never()).save(Mockito.any(CognitoTokens::class.java) ?: tokens, Mockito.anyString())
    }

    @Test fun nextLoginWaitsForAccountWipe() = runTest {
        val release = CompletableDeferred<Unit>()
        var wiped = false
        val subject = AuthRepository(client, store, credentials, { release.await(); wiped = true }, backgroundScope)
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.login(config, "new-user", "password", null)).thenAnswer {
            assertTrue(wiped)
            CognitoAuthResult.Authenticated(tokens)
        }
        subject.signOut()
        val login = async { subject.login("new-user", "password") }
        runCurrent()
        assertFalse(login.isCompleted)
        Mockito.verifyNoInteractions(client)
        release.complete(Unit)
        assertEquals(LoginOutcome.Success, login.await())
    }

    @Test fun signOutThenProcessRestartStillWipesBeforeLogin() = runTest {
        val neverFinished = CompletableDeferred<Unit>()
        val oldProcess = AuthRepository(client, store, credentials, { neverFinished.await() }, backgroundScope)
        oldProcess.signOut()
        // Persisted token state survives, but in-memory Deferred and repository do not.
        Mockito.`when`(store.hasRefreshToken()).thenReturn(false)
        var wiped = false
        val restarted = AuthRepository(client, store, credentials, { wiped = true }, backgroundScope)
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.login(config, "new-user", "password", null)).thenAnswer {
            assertTrue(wiped, "local account data must be gone before authentication succeeds")
            CognitoAuthResult.Authenticated(tokens)
        }
        assertEquals(LoginOutcome.Success, restarted.login("new-user", "password"))
    }

    @Test fun wipeFailureBlocksLoginAndTokenAccess() = runTest {
        val supervised = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext +
            kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val subject = AuthRepository(client, store, credentials, { throw IOException("wipe failed") }, supervised)
        subject.signOut()
        assertFailsWith<IOException> { subject.login("new-user", "password") }
        assertFailsWith<HiveAuthException> { subject.currentIdToken() }
        Mockito.verifyNoInteractions(client)
    }

    @Test fun sameAccountReauthKeepsHistoryButDifferentAccountWipes() = runTest {
        Mockito.`when`(store.hasRefreshToken()).thenReturn(true)
        Mockito.`when`(credentials.username()).thenReturn("saved-user")
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.login(config, "saved-user", "password", null))
            .thenReturn(CognitoAuthResult.Authenticated(tokens))
        Mockito.`when`(client.login(config, "other-user", "password", null))
            .thenReturn(CognitoAuthResult.Authenticated(tokens))
        var wipes = 0
        val subject = AuthRepository(client, store, credentials, { wipes++ }, backgroundScope)
        assertEquals(LoginOutcome.Success, subject.login("saved-user", "password"))
        assertEquals(0, wipes)
        assertEquals(LoginOutcome.Success, subject.login("other-user", "password"))
        assertEquals(1, wipes)
    }

    @Test fun smsChallengeUsesCognitoCanonicalUsernameAndSignOutInvalidatesIt() = runTest {
        val subject = AuthRepository(client, store, credentials, {}, backgroundScope)
        Mockito.`when`(client.discoverPoolConfig()).thenReturn(config)
        Mockito.`when`(client.login(config, "email-user", "password", null))
            .thenReturn(CognitoAuthResult.SmsMfaRequired("challenge", "canonical-user-id"))
        Mockito.`when`(client.submitSmsCode(config, "123456", "challenge", "canonical-user-id", null))
            .thenReturn(tokens)
        assertIs<LoginOutcome.SmsCodeRequired>(subject.login("email-user", "password"))
        subject.submitSmsCode("123456", "challenge", "canonical-user-id")
        Mockito.verify(store).save(tokens, "sms_mfa")
        subject.signOut()
        assertFailsWith<IllegalStateException> {
            subject.submitSmsCode("123456", "challenge", "canonical-user-id")
        }
    }

}
