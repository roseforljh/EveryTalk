package com.android.everytalk.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.websocket.WebSockets
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.dsl.koinApplication

class NetworkModuleTest {

    @Test
    fun `network module http client installs websockets plugin`() {
        val app = koinApplication {
            modules(networkModule)
        }

        val client = app.koin.get<HttpClient>()

        assertNotNull(client.plugin(WebSockets))
        client.close()
        app.close()
    }
}
