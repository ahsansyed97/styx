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
import com.hotels.styx.api.HttpResponseStatus
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

fun <T> Publisher<T>.await() : T = Mono.from(this).block()!!

fun ResponseDefinitionBuilder.withHeader(name : CharSequence, value : CharSequence): ResponseDefinitionBuilder = withHeader(name.toString(), value.toString())
fun ResponseDefinitionBuilder.withHeader(name : CharSequence, value : Int): ResponseDefinitionBuilder = withHeader(name.toString(), value.toString())

fun ResponseDefinitionBuilder.withStatus(status : HttpResponseStatus) : ResponseDefinitionBuilder = withStatus(status.code())
