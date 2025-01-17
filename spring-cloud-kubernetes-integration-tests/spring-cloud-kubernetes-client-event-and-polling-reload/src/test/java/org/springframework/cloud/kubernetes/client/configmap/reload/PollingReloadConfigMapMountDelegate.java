/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.kubernetes.client.configmap.reload;

import java.time.Duration;
import java.util.Map;

import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import org.junit.jupiter.api.Assertions;
import org.testcontainers.k3s.K3sContainer;

import org.springframework.cloud.kubernetes.integration.tests.commons.Commons;
import org.springframework.cloud.kubernetes.integration.tests.commons.native_client.Util;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import static org.awaitility.Awaitility.await;

/**
 * @author wind57
 */
final class PollingReloadConfigMapMountDelegate {

	private PollingReloadConfigMapMountDelegate() {

	}

	/**
	 * <pre>
	 *     - we have "spring.config.import: kubernetes", which means we will 'locate' property sources
	 *       from config maps.
	 *     - the property above means that at the moment we will be searching for config maps that only
	 *       match the application name, in this specific test there is no such config map.
	 *     - what we will also read, is 'spring.cloud.kubernetes.config.paths', which we have set to
	 *     	 '/tmp/application.properties'
	 *       in this test. That is populated by the volumeMounts (see BODY_FIVE)
	 *     - we first assert that we are actually reading the path based source via (1), (2) and (3).
	 *
	 *     - we then change the config map content, wait for k8s to pick it up and replace them
	 *     - our polling will then detect that change, and trigger a reload.
	 * </pre>
	 */
	static void testPollingReloadConfigMapMount(String deploymentName, K3sContainer k3sContainer, Util util,
			String imageName) throws Exception {

		K8sClientConfigMapReloadITUtil.patchFive(deploymentName, "default", imageName);

		// (1)
		Commons.waitForLogStatement("paths property sources : [/tmp/application.properties]", k3sContainer,
				deploymentName);

		// (2)
		Commons.waitForLogStatement("will add file-based property source : /tmp/application.properties", k3sContainer,
				deploymentName);

		// (3)
		WebClient webClient = K8sClientConfigMapReloadITUtil.builder().baseUrl("http://localhost/mount").build();
		String result = webClient.method(HttpMethod.GET).retrieve().bodyToMono(String.class)
				.retryWhen(K8sClientConfigMapReloadITUtil.retrySpec()).block();

		// we first read the initial value from the configmap
		Assertions.assertEquals("as-mount-initial", result);

		// replace data in configmap and wait for k8s to pick it up
		// our polling will detect that and restart the app
		V1ConfigMap configMap = (V1ConfigMap) util.yaml("configmap-mount.yaml");
		configMap.setData(Map.of("application.properties", "from.properties.key=as-mount-changed"));
		new CoreV1Api().replaceNamespacedConfigMap("poll-reload-as-mount", "default", configMap, null, null, null,
				null);

		await().timeout(Duration.ofSeconds(180))
				.until(() -> webClient.method(HttpMethod.GET).retrieve().bodyToMono(String.class)
						.retryWhen(K8sClientConfigMapReloadITUtil.retrySpec()).block().equals("as-mount-changed"));

	}

}
