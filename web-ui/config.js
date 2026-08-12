/*
 * Cake Delight demo client - runtime configuration.
 *
 * The gateway base URL is not hardcoded in app.js. This file is the single place it is
 * set, so the same static bundle runs anywhere:
 *
 *   Docker Compose  the gateway publishes 8080 on the host, so the default below works
 *                   as-is once the UI is opened at http://localhost:3000
 *   Kubernetes      k8s/web-ui/configmap.yaml supplies its own config.js, mounted over
 *                   this file, pointing at the api-gateway NodePort
 *
 * The browser runs outside the cluster and the Docker network, so this value must be an
 * address the browser itself can reach. In-cluster names such as http://api-gateway:8080
 * will not resolve here.
 *
 * Whatever origin is set here must also appear in the gateway's
 * spring.cloud.gateway.globalcors allowed-origins list, or the browser blocks the calls.
 */
window.CAKE_DELIGHT_API_BASE = 'http://localhost:8080';
