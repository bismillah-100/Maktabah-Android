#include <jni.h>
#include <string>
#include <android/log.h>
#include "sqlite3.h"

#define LOG_TAG "SQLiteDB_Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void throwSQLiteException(JNIEnv *env, const char *message) {
    jclass exClass = env->FindClass("java/lang/Exception");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_maktabah_database_SQLiteDB_open(JNIEnv* env, jobject /* this */, jstring path, jint flags) {
    const char *db_path = env->GetStringUTFChars(path, nullptr);
    sqlite3 *db = nullptr;
    int rc = sqlite3_open_v2(db_path, &db, flags, nullptr);
    env->ReleaseStringUTFChars(path, db_path);
    if (rc != SQLITE_OK) {
        std::string errMsg = "open failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        if (db) sqlite3_close(db);
        throwSQLiteException(env, errMsg.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(db);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteDB_close(JNIEnv* env, jobject /* this */, jlong dbPtr) {
    sqlite3 *db = reinterpret_cast<sqlite3 *>(dbPtr);
    if (!db) return SQLITE_OK;
    return sqlite3_close_v2(db);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_maktabah_database_SQLiteDB_prepare(JNIEnv* env, jobject /* this */, jlong dbPtr, jstring sql) {
    sqlite3 *db = reinterpret_cast<sqlite3 *>(dbPtr);
    if (!db) return 0;
    const char *c_sql = env->GetStringUTFChars(sql, nullptr);
    sqlite3_stmt *stmt = nullptr;
    int rc = sqlite3_prepare_v2(db, c_sql, -1, &stmt, nullptr);
    if (rc != SQLITE_OK) {
        std::string errMsg = "prepare failed [" + std::to_string(rc) + "]: " + sqlite3_errmsg(db) + " | SQL: " + c_sql;
        LOGE("%s", errMsg.c_str());
        env->ReleaseStringUTFChars(sql, c_sql);
        if (stmt) sqlite3_finalize(stmt);
        throwSQLiteException(env, errMsg.c_str());
        return 0;
    }
    env->ReleaseStringUTFChars(sql, c_sql);
    return reinterpret_cast<jlong>(stmt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_step(JNIEnv* env, jobject /* this */, jlong stmtPtr) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) {
        throwSQLiteException(env, "Statement pointer is null in step");
        return SQLITE_ERROR;
    }
    int rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW && rc != SQLITE_DONE && rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "step failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_reset(JNIEnv* env, jobject /* this */, jlong stmtPtr) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    return sqlite3_reset(stmt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_clearBindings(JNIEnv* env, jobject /* this */, jlong stmtPtr) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    return sqlite3_clear_bindings(stmt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_finalize(JNIEnv* env, jobject /* this */, jlong stmtPtr) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_OK;
    return sqlite3_finalize(stmt);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_bindInt(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint index, jint val) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    int rc = sqlite3_bind_int(stmt, index, val);
    if (rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "bindInt failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_maktabah_database_SQLiteStmt_columnText(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint col) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    const unsigned char *text = sqlite3_column_text(stmt, col);
    if (!text) return nullptr;
    return env->NewStringUTF(reinterpret_cast<const char *>(text));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_columnInt(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint col) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    return sqlite3_column_int(stmt, col);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_maktabah_database_SQLiteStmt_columnBlob(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint col) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    const void *blob = sqlite3_column_blob(stmt, col);
    int size = sqlite3_column_bytes(stmt, col);
    if (!blob || size <= 0) return nullptr;
    jbyteArray arr = env->NewByteArray(size);
    env->SetByteArrayRegion(arr, 0, size, reinterpret_cast<const jbyte *>(blob));
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_maktabah_database_SQLiteDB_lastInsertRowId(JNIEnv* env, jobject /* this */, jlong dbPtr) {
    sqlite3 *db = reinterpret_cast<sqlite3 *>(dbPtr);
    if (!db) return 0;
    return sqlite3_last_insert_rowid(db);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_bindText(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint index, jstring val) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    if (!val) {
        int rc = sqlite3_bind_null(stmt, index);
        if (rc != SQLITE_OK) throwSQLiteException(env, "bindText (null) failed");
        return rc;
    }
    const char *c_val = env->GetStringUTFChars(val, nullptr);
    int rc = sqlite3_bind_text(stmt, index, c_val, -1, SQLITE_TRANSIENT);
    env->ReleaseStringUTFChars(val, c_val);
    if (rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "bindText failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_bindBlob(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint index, jbyteArray val) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    if (!val) {
        int rc = sqlite3_bind_null(stmt, index);
        if (rc != SQLITE_OK) throwSQLiteException(env, "bindBlob (null) failed");
        return rc;
    }
    jsize len = env->GetArrayLength(val);
    jbyte *bytes = env->GetByteArrayElements(val, nullptr);
    int rc = sqlite3_bind_blob(stmt, index, bytes, len, SQLITE_TRANSIENT);
    env->ReleaseByteArrayElements(val, bytes, JNI_ABORT);
    if (rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "bindBlob failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_bindNull(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint index) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    int rc = sqlite3_bind_null(stmt, index);
    if (rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "bindNull failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_bindLong(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint index, jlong val) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    if (!stmt) return SQLITE_ERROR;
    int rc = sqlite3_bind_int64(stmt, index, val);
    if (rc != SQLITE_OK) {
        sqlite3 *db = sqlite3_db_handle(stmt);
        std::string errMsg = "bindLong failed [" + std::to_string(rc) + "]: " + (db ? sqlite3_errmsg(db) : "unknown");
        LOGE("%s", errMsg.c_str());
        throwSQLiteException(env, errMsg.c_str());
    }
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_maktabah_database_SQLiteStmt_columnType(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint col) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    return sqlite3_column_type(stmt, col);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_maktabah_database_SQLiteStmt_columnLong(JNIEnv* env, jobject /* this */, jlong stmtPtr, jint col) {
    sqlite3_stmt *stmt = reinterpret_cast<sqlite3_stmt *>(stmtPtr);
    return sqlite3_column_int64(stmt, col);
}
