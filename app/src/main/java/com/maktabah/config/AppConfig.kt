package com.maktabah.config

object AppConfig {
    const val GOOGLE_DRIVE_DOWNLOAD_BASE = "https://drive.google.com/uc?export=download&id="
    const val BOOK_UPDATE_MAIN_CSV_URL = "${GOOGLE_DRIVE_DOWNLOAD_BASE}1FYrscpCBIuIym2ZHB6QBwYfy9eswYDna"
    const val BOOK_UPDATE_AUTH_CSV_URL = "${GOOGLE_DRIVE_DOWNLOAD_BASE}1Aekhq21Ihsxr1sAhnJSxZxA59yCxmEmq"

    object Prefs {
        const val LIBRARY_PREFS = "library_prefs"
        const val KEY_LAST_UPDATE_CHECK = "last_book_update_check"
        const val KEY_CACHED_UPDATE_COUNT = "cached_book_update_count"
    }

    object WorkManagerKeys {
        const val BOOK_UPDATE_PERIODIC_WORK = "BookUpdateCheckWorker"
    }
}
