# Kubernetes manifests

Deployment configuration for every Cake Delight component. All resources are namespaced to
`cake-delight`.

> **Status: these manifests have not been applied to a cluster.** No Kubernetes cluster and no Docker
> engine were available in the development environment, so `kubectl apply` was never run against them.
> Everything below is the intended procedure rather than a recorded one, and the YAML should be treated
> as reviewed but unproven.
>
> The path that *has* been run end to end is Path B in the [root README](../README.md#path-b--local-jvm-processes-no-docker):
> five JVM processes, Kafka, and the UI on a single host.

---

## What is here

| Directory | Contents | Service type |
|---|---|---|
| `namespace.yaml` | the `cake-delight` namespace | — |
| `postgres/` | one PVC + Deployment + Service per database, plus `secret.yaml` holding all four credential pairs | ClusterIP |
| `kafka/` | single-node KRaft broker, `replicas: 1`, `strategy: Recreate` | ClusterIP |
| `catalog-service/` | deployment, service, configmap, secret, hpa | ClusterIP `:8081` |
| `order-service/` | deployment, service, configmap, secret, hpa | ClusterIP `:8082` |
| `rating-service/` | deployment, service, configmap, secret, hpa | ClusterIP `:8083` |
| `notification-service/` | deployment, service, configmap, secret, hpa | ClusterIP `:8084` |
| `api-gateway/` | deployment, service, configmap, secret, hpa | **NodePort 30080** |
| `web-ui/` | deployment, service, configmap, hpa | **NodePort 30090** |

Two NodePorts are reachable from outside the cluster: `30090` for the browser client and `30080`
for the API. Everything else — the four services, PostgreSQL, Kafka — is ClusterIP, so `8081`–`8084`,
`5432`, and `9092` cannot be reached from off-cluster.

`web-ui` has no `secret.yaml`. It reads no credential and its Deployment declares no `envFrom`, so
there is nothing for an empty Secret to do. `api-gateway/secret.yaml` *is* intentionally empty,
because that Deployment does declare an `envFrom.secretRef` that has to resolve.

## In-cluster DNS names

Short names resolve within the namespace. Callers use these and nothing else; no IP is hardcoded
anywhere in these files.

| Name | Port | Used by |
|---|---|---|
| `catalog-db` | 5432 | catalog-service only |
| `order-db` | 5432 | order-service only |
| `rating-db` | 5432 | rating-service only |
| `notification-db` | 5432 | notification-service only |
| `kafka` | 9092 | order-service (producer), notification-service (consumer) |
| `catalog-service` | 8081 | api-gateway, order-service |
| `order-service` | 8082 | api-gateway |
| `rating-service` | 8083 | api-gateway |
| `notification-service` | 8084 | api-gateway |
| `api-gateway` | 8080 | web-ui (nginx `/api/` proxy) |

One database per service, and a service's Secret only grants access to the database it owns. That is
the database-per-service boundary expressed in RBAC-free terms.

---

## Deploying

### 1. Build the images and load them into the cluster

Nothing is pushed to a registry, so every Deployment sets `imagePullPolicy: IfNotPresent` and the
images have to be present on the node already. Build them first from the repository root:

```bash
docker compose build
```

Then load each one. For minikube:

```bash
for c in catalog-service order-service rating-service notification-service api-gateway web-ui; do
  minikube image load cake-delight/$c:1.0.0
done
```

For kind, substitute `kind load docker-image cake-delight/$c:1.0.0`.

Skip this and the pods sit in `ErrImageNeverPull` / `ImagePullBackOff`, because
`cake-delight/*:1.0.0` exists on no public registry.

`postgres:16.4`, `apache/kafka:3.7.1`, and `nginx:1.27-alpine` are pulled normally. Every tag is
pinned; `latest` appears nowhere.

### 2. Replace the placeholder secrets

Every committed `secret.yaml` carries `REPLACE_ME`. These are placeholders, not credentials — no real
credential is committed anywhere in this repository. Deploying without replacing them gives you four
databases whose password is the literal string `REPLACE_ME`, which will at least start, and four
services that can authenticate against them, which is worse than failing loudly.

Two Secrets exist per database and they describe the **same account** from two sides:

| Secret | Keys | Consumed by |
|---|---|---|
| `<name>-db-credentials` | `POSTGRES_USER`, `POSTGRES_PASSWORD` | the PostgreSQL container, which *creates* the account |
| `<name>-service-credentials` | `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | the service, which *authenticates* with it |

They must match per database, or the service fails its readiness probe on a Flyway authentication
error. Generate both from the same values:

```bash
kubectl apply -f k8s/namespace.yaml

USER=cakedelight
PASS="$(openssl rand -base64 24)"

for db in catalog order rating notification; do
  kubectl -n cake-delight create secret generic ${db}-db-credentials \
    --from-literal=POSTGRES_USER="$USER" \
    --from-literal=POSTGRES_PASSWORD="$PASS" \
    --dry-run=client -o yaml | kubectl apply -f -

  kubectl -n cake-delight create secret generic ${db}-service-credentials \
    --from-literal=SPRING_DATASOURCE_USERNAME="$USER" \
    --from-literal=SPRING_DATASOURCE_PASSWORD="$PASS" \
    --dry-run=client -o yaml | kubectl apply -f -
done
```

Do this **before** step 3, and note that a bulk `kubectl apply -R -f k8s/` afterwards will overwrite
these with the `REPLACE_ME` versions again. If you would rather keep the ordering simple, edit the
`stringData` blocks in place and never commit the result.

Also apply `k8s/api-gateway/secret.yaml` as-is; it is empty by design and exists only so the
gateway's `envFrom.secretRef` resolves.

### 3. Apply the manifests

Namespace first — nothing else can be created without it:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -R -f k8s/
```

`-R` is not optional. The manifests live in per-component subdirectories and a plain `-f k8s/` reads
only `namespace.yaml`, silently skipping every other file.

Apply order beyond the namespace does not matter. Kubernetes is declarative and the probes handle
sequencing: the services' readiness groups include `db`, so a service pod stays out of its Service's
endpoint list until its database is reachable and Flyway has finished migrating. There is no
init-container ordering to get right.

### 4. Verify

```bash
kubectl -n cake-delight get pods -o wide
kubectl -n cake-delight get svc
kubectl -n cake-delight rollout status deployment/catalog-service
```

Expect the first `Running` well before the first `Ready`: readiness waits on the database and the
migration. `catalog-service` has `readinessProbe.initialDelaySeconds: 30` and
`failureThreshold: 12`, so it tolerates roughly two and a half minutes of database startup plus
Flyway before it gives up.

If a pod never reaches `Ready`:

```bash
kubectl -n cake-delight describe pod <pod>       # events: image pull, mount, probe failures
kubectl -n cake-delight logs <pod>               # Flyway and datasource errors land here
```

### 5. Reach it

```bash
minikube service web-ui -n cake-delight --url        # the storefront
minikube service api-gateway -n cake-delight --url   # the API, for curl and Postman
```

Or use the fixed NodePorts directly: `http://<node-ip>:30090` and `http://<node-ip>:30080`.

Without minikube, port-forward instead — both are long-running, so run them yourself:

```bash
kubectl -n cake-delight port-forward svc/web-ui 8090:80
kubectl -n cake-delight port-forward svc/api-gateway 8080:8080
```

---

## Scaling

### What has an HPA, and what does not

An HPA exists for each of the six stateless components: `catalog-service`, `order-service`,
`rating-service`, `notification-service`, `api-gateway`, and `web-ui`. All six hold no session state
and write nothing to local disk, so replicas are interchangeable and the ClusterIP or NodePort
Service spreads traffic across the ready endpoints. All six are `minReplicas: 1`, `maxReplicas: 3`,
CPU `averageUtilization: 70` — a deliberately modest ceiling for a single-node demo cluster.

The four PostgreSQL Deployments and Kafka have **no** HPA, and that is a design decision rather than
an omission. Each PostgreSQL pod is backed by a single `ReadWriteOnce` PVC, so a second replica would
either fail to attach the volume or corrupt the data directory. Kafka is pinned for a different
reason: it is one KRaft process acting as its own controller quorum, with a fixed `KAFKA_NODE_ID` of
`1` and `KAFKA_CONTROLLER_QUORUM_VOTERS` of `1@localhost:9093`, so a second pod from the same
template would claim the same identity and the same single-voter quorum. Its log directory is an
`emptyDir`, not a PVC — scratch space for a demo broker.

Scaling a database means read replicas or sharding; scaling a KRaft cluster means distinct broker
identities and a multi-voter quorum. Both are different designs, not a replica-count change. All five
therefore use `strategy: Recreate`, so a rolling update can never briefly run two pods against the
same data.

### metrics-server is required

Every HPA targets CPU utilization, and utilization is a percentage of the pod's CPU *request* —
which is why each Deployment declares one (`200m` for the JVM services, `50m` for `web-ui`). The
numbers come from the metrics API, which a bare cluster does not serve.

```bash
minikube addons enable metrics-server
# or, for a generic cluster:
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

kubectl -n cake-delight get hpa
```

Without it the `TARGETS` column reads `<unknown>/70%`, no scaling decision is ever made, and the
replica count stays at `minReplicas` forever. The HPA does not error; it just quietly does nothing.

### Where scaling stops helping

`notification-service` is the hard ceiling. The `order.completed` topic has **one partition**, and
Kafka assigns each partition to at most one consumer in a group. Every replica past the first joins
the `notification-service` group, is assigned nothing, and idles. Its HPA can scale it to 3, and
throughput will not move. Raising the partition count is the only fix, and that is deliberately out
of scope for this increment.

`web-ui` replicas widen the static-file and proxy tier only. Every `/api/` call still funnels through
`api-gateway` and then the owning service, so a slow catalog read is unaffected by more UI pods.

Databases cap everything else. Six application components scaling to 3 each still share four
single-instance PostgreSQL pods.

---

## Known gaps

Honest list, since none of this has been executed:

- **Parsed, but not schema-validated.** Every `.yaml` file here has been parsed with SnakeYAML (the
  same parser Spring Boot uses) and all of them load cleanly, including the multi-document
  `postgres/` files. So indentation, block structure, and duplicate keys are ruled out.
  What is *not* ruled out is anything schema-level: a misspelled field, a wrong enum value, a field
  on the wrong type. `kubectl create --dry-run=client` cannot close that gap without a cluster: even a
  client-side dry run pulls the schema and resolves the REST mapping through the API server, and
  against no cluster it fails with `failed to download openapi: the server could not find the
  requested resource`. Schema checking needs a reachable cluster or a standalone validator such as
  `kubeconform`.
- **Not applied.** No cluster and no container runtime were available in the development environment,
  so no image was built from the Dockerfiles and no manifest here was applied.
- **PVCs assume a default StorageClass.** The `postgres/` PVCs name no `storageClassName`, so they
  bind through whatever the cluster's default provisioner is. On a cluster with no default
  StorageClass they stay `Pending` and the database pods never start.
- **No Ingress and no TLS.** Two NodePorts serve as the entry points. Fine for a local demo cluster,
  not for anything shared.
- **No NetworkPolicy.** ClusterIP keeps the services off the external network, but any pod in the
  cluster can still reach any other. The database-per-service boundary is enforced by configuration
  and credentials, not by the network.
- **No PodDisruptionBudget and no anti-affinity.** On a single-node cluster neither would do
  anything useful.
- **Authentication is out of scope** for this increment, per the project's scope discipline, so no
  component authenticates a caller.
