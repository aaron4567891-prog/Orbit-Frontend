package au.orbit.frontend

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Share TLS sessions and HTTP connections across every scraper worker; no disk response cache. */
object ScraperHttp {
    val client=OkHttpClient.Builder()
        .connectionPool(ConnectionPool(12,5,TimeUnit.MINUTES))
        .connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(45,TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followSslRedirects(false)
        .build()
}
