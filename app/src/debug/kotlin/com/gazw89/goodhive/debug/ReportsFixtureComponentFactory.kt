package com.gazw89.goodhive.debug

import android.app.Application
import androidx.core.app.CoreComponentFactory

/**
 * The fixture process must not start GoodHiveApplication: it schedules real polling work.
 * All normal app components still use the production Application and AndroidX factory behavior.
 */
class ReportsFixtureComponentFactory : CoreComponentFactory() {
    override fun instantiateApplication(cl: ClassLoader, className: String): Application =
        if (Application.getProcessName().endsWith(":reports_fixture")) {
            Application()
        } else {
            super.instantiateApplication(cl, className)
        }
}
