/*
  Copyright (C) 2013-2022 Expedia Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.hotels.styx.client

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.hotels.styx.api.HttpHeaderNames.CONTENT_LENGTH
import com.hotels.styx.api.HttpResponseStatus.OK
import com.hotels.styx.api.LiveHttpRequest
import com.hotels.styx.api.MicrometerRegistry
import com.hotels.styx.api.exceptions.ResponseTimeoutException
import com.hotels.styx.api.extension.ActiveOrigins
import com.hotels.styx.api.extension.Origin
import com.hotels.styx.api.extension.Origin.newOriginBuilder
import com.hotels.styx.api.extension.loadbalancing.spi.LoadBalancer
import com.hotels.styx.api.extension.service.BackendService
import com.hotels.styx.client.OriginsInventory.newOriginsInventoryBuilder
import com.hotels.styx.client.StyxBackendServiceClient.newHttpClientBuilder
import com.hotels.styx.client.loadbalancing.strategies.BusyConnectionsStrategy
import com.hotels.styx.metrics.CentralisedMetrics
import com.hotels.styx.support.Support.requestContext
import com.hotels.styx.support.server.FakeHttpServer
import com.hotels.styx.support.server.UrlMatchingStrategies.urlStartingWith
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.matchers.shouldBeInRange
import io.kotlintest.specs.StringSpec
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import java.lang.System.currentTimeMillis
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicLong

class BackendServiceClientSpec : StringSpec() {
    private val originOneServer = FakeHttpServer(0)
    private val responseTimeout = 1000

    private lateinit var webappOrigin: Origin
    private lateinit var client: StyxBackendServiceClient

    override fun beforeSpec(spec: Spec) {
        originOneServer.start()
        webappOrigin = newOriginBuilder("localhost", originOneServer.port()).applicationId("webapp").id("webapp-01").build()
    }

    override fun beforeTest(testCase: TestCase) {
        originOneServer.reset()

        val backendService = BackendService.Builder()
            .origins(webappOrigin)
            .responseTimeoutMillis(responseTimeout)
            .build()

        client = newHttpClientBuilder(backendService.id())
            .metrics(CentralisedMetrics(MicrometerRegistry(SimpleMeterRegistry())))
            .loadBalancer(busyConnectionStrategy(activeOrigins(backendService)))
            .build()
    }

    override fun afterSpec(spec: Spec) {
        originOneServer.stop()
    }

    init {
        "Emits an HTTP response even when content observable remains un-subscribed." {
            originOneServer.stub(urlStartingWith("/"), response200OkWithContentLengthHeader("Test message body."))
            val response = client.sendRequest(LiveHttpRequest.get("/foo/1").build(), requestContext()).await()
            assert(response.status() == OK) { "\nDid not get response with 200 OK status.\n$response\n" }
        }

        "Emits an HTTP response containing Content-Length from persistent connection that stays open." {
            originOneServer.stub(urlStartingWith("/"), response200OkWithContentLengthHeader("Test message body."))

            val response = Mono.from(client.sendRequest(LiveHttpRequest.get("/foo/2").build(), requestContext()))
                .flatMap { Mono.from(it.aggregate(10000)) }
                .block()!!

            assert(response.status() == OK) { "\nDid not get response with 200 OK status.\n$response\n" }
            assert(response.bodyAs(UTF_8) == "Test message body.") { "\nReceived wrong/unexpected response body." }
        }

        "Emits onError when origin responds too slowly" {
            val start = AtomicLong()
            originOneServer.stub(
                urlStartingWith("/"), aResponse()
                    .withStatus(OK)
                    .withFixedDelay(3000)
            )

            assertThrows<ResponseTimeoutException>("- Client emitted an incorrect exception!") {
                Mono.from(client.sendRequest(LiveHttpRequest.get("/foo/4").build(), requestContext()))
                    .doOnSubscribe { start.set(currentTimeMillis()) }
                    .block()
            }

            val duration = currentTimeMillis() - start.get()

            duration shouldBeInRange (responseTimeout.toLong() - 250)..(responseTimeout.toLong() + 250)
        }
    }

    private fun activeOrigins(backendService: BackendService): ActiveOrigins = newOriginsInventoryBuilder(
        CentralisedMetrics(
            MicrometerRegistry(
                CompositeMeterRegistry()
            )
        ), backendService
    ).build()

    private fun busyConnectionStrategy(activeOrigins: ActiveOrigins): LoadBalancer = BusyConnectionsStrategy(activeOrigins)

    private fun response200OkWithContentLengthHeader(content: String): ResponseDefinitionBuilder = aResponse()
        .withStatus(OK)
        .withHeader(CONTENT_LENGTH, content.length)
        .withBody(content)
}
