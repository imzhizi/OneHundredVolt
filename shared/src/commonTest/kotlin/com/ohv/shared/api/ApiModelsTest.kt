package com.ohv.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `album catalog error response without data remains decodable`() {
        val response = json.decodeFromString<AlbumCatalogResponse>(
            """{"ec":429,"em":"请求过于频繁"}"""
        )

        assertEquals(429, response.ec)
        assertEquals("请求过于频繁", response.em)
        assertNull(response.data)
    }
}
