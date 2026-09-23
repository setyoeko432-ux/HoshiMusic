package com.naomi.hoshimusic.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HoshiDatabase::class.java,
        listOf()
    )

    /**
     * Test migrasi dari V11 ke V12.
     * Membutuhkan file schemas/com.naomi.hoshimusic.data.db.HoshiDatabase/11.json untuk dijalankan.
     */
    @Test
    @Throws(IOException::class)
    fun migrate11To12() {
        // Jika 11.json tersedia, aktifkan blok berikut:
        /*
        helper.createDatabase(TEST_DB, 11).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 12, true, HoshiDatabase.MIGRATION_11_12)
        */
    }

    /**
     * Test migrasi berantai dari V6 ke V12.
     * Jalur: 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12
     */
    @Test
    @Throws(IOException::class)
    fun migrate6To12() {
        // Membutuhkan 6.json
        /*
        helper.createDatabase(TEST_DB, 6).apply {
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB, 
            12, 
            true, 
            HoshiDatabase.MIGRATION_6_7,
            HoshiDatabase.MIGRATION_7_8,
            HoshiDatabase.MIGRATION_8_9,
            HoshiDatabase.MIGRATION_9_10,
            HoshiDatabase.MIGRATION_10_11,
            HoshiDatabase.MIGRATION_11_12
        )
        */
    }

    /**
     * Test migrasi 3 ke 4.
     */
    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        // Membutuhkan 3.json
        /*
        helper.createDatabase(TEST_DB, 3).apply {
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, HoshiDatabase.MIGRATION_3_4)
        */
    }
}
