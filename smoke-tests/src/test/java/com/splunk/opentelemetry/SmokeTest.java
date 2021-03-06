/*
 * Copyright Splunk Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.splunk.opentelemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public abstract class SmokeTest {
  private static final Logger logger = LoggerFactory.getLogger(SmokeTest.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected static OkHttpClient client = OkHttpUtils.client();

  private static final Network network = Network.newNetwork();
  public static final String agentPath =
      System.getProperty("io.opentelemetry.smoketest.agent.shadowJar.path");

  protected boolean localDockerImageIsPresent(String imageName) {
    try {
      DockerClientFactory.lazyClient().inspectImageCmd(imageName).exec();
      return true;
    } catch (Exception e) {
      System.out.println(e.getMessage());
      return false;
    }
  }

  /** Subclasses can override this method to customise target application's environment */
  protected Map<String, String> getExtraEnv() {
    return Collections.emptyMap();
  }

  private static GenericContainer backend;
  private static GenericContainer collector;

  @BeforeAll
  static void setupSpec() {
    backend =
        new GenericContainer<>(
                DockerImageName.parse(
                    "open-telemetry-docker-dev.bintray.io/java/smoke-fake-backend:latest"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forPort(8080))
            .withNetwork(network)
            .withNetworkAliases("backend")
            .withLogConsumer(new Slf4jLogConsumer(logger));
    backend.start();

    collector =
        new GenericContainer<>(DockerImageName.parse("otel/opentelemetry-collector-dev:latest"))
            .dependsOn(backend)
            .withNetwork(network)
            .withNetworkAliases("collector")
            .withLogConsumer(new Slf4jLogConsumer(logger))
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("/otel.yaml"), "/etc/otel.yaml")
            .withCommand("--config /etc/otel.yaml");
    collector.start();
  }

  protected GenericContainer target;

  void startTarget(String targetImageName) {
    target =
        new GenericContainer<>(DockerImageName.parse(targetImageName))
            .withStartupTimeout(Duration.ofMinutes(5))
            .withExposedPorts(8080)
            .withNetwork(network)
            .withLogConsumer(new Slf4jLogConsumer(logger))
            .withCopyFileToContainer(
                MountableFile.forHostPath(agentPath), "/opentelemetry-javaagent.jar")
            .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent.jar")
            .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
            .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
            .withEnv("OTEL_EXPORTER_JAEGER_ENDPOINT", "http://collector:14268/api/traces")
            .withEnv(getExtraEnv());
    WaitStrategy waitStrategy = getWaitStrategy();
    if (waitStrategy != null) {
      target = target.waitingFor(waitStrategy);
    }
    target.start();
  }

  protected WaitStrategy getWaitStrategy() {
    return null;
  }

  @AfterEach
  void cleanup() throws IOException {
    resetBackend();
  }

  protected void resetBackend() throws IOException {
    client
        .newCall(
            new Request.Builder()
                .url(
                    String.format(
                        "http://localhost:%d/clear-requests", backend.getMappedPort(8080)))
                .build())
        .execute()
        .close();
  }

  void stopTarget() {
    target.stop();
  }

  @AfterAll
  static void cleanupSpec() {
    backend.stop();
    collector.stop();
  }

  protected static Stream<AnyValue> findResourceAttribute(
      Collection<ExportTraceServiceRequest> traces, String attributeKey) {
    return traces.stream()
        .flatMap(it -> it.getResourceSpansList().stream())
        .flatMap(it -> it.getResource().getAttributesList().stream())
        .filter(it -> it.getKey().equals(attributeKey))
        .map(KeyValue::getValue);
  }

  protected TraceInspector waitForTraces() throws IOException, InterruptedException {
    String content = waitForContent();

    return new TraceInspector(
        StreamSupport.stream(OBJECT_MAPPER.readTree(content).spliterator(), false)
            .map(
                it -> {
                  ExportTraceServiceRequest.Builder builder =
                      ExportTraceServiceRequest.newBuilder();
                  // TODO(anuraaga): Register parser into object mapper to avoid de -> re ->
                  // deserialize.
                  try {
                    JsonFormat.parser().merge(OBJECT_MAPPER.writeValueAsString(it), builder);
                  } catch (InvalidProtocolBufferException | JsonProcessingException e) {
                    e.printStackTrace();
                  }
                  return builder.build();
                })
            .collect(Collectors.toList()));
  }

  private String waitForContent() throws IOException, InterruptedException {
    long previousSize = 0;
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
    String content = "[]";
    while (System.currentTimeMillis() < deadline) {

      Request request =
          new Request.Builder()
              .url(String.format("http://localhost:%d/get-requests", backend.getMappedPort(8080)))
              .build();

      try (ResponseBody body = client.newCall(request).execute().body()) {
        content = body.string();
      }

      if (content.length() > 2 && content.length() == previousSize) {
        break;
      }
      previousSize = content.length();
      System.out.printf("Current content size %d%n", previousSize);
      TimeUnit.MILLISECONDS.sleep(500);
    }

    return content;
  }

  protected String getCurrentAgentVersion() throws IOException {
    return new JarFile(agentPath)
        .getManifest()
        .getMainAttributes()
        .get(Attributes.Name.IMPLEMENTATION_VERSION)
        .toString();
  }
}
