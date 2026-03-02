# Distributed Bully

Distributed implementation of the [Bully Election Algorithm](https://en.wikipedia.org/wiki/Bully_algorithm) across multiple servers. Processes are distributed round-robin across Tomcat instances and communicate through REST APIs with a server-mediated fan-out optimization — election messages are sent once per server, and each server internally relays them to all relevant local processes. Includes a CLI manager to control processes in real time.

Built as a university project for the **Sistemas Distribuidos (SSDD)** course.

## How it works

The [Bully Algorithm](https://en.wikipedia.org/wiki/Bully_algorithm) (Garcia-Molina, 1982) is a leader election protocol for distributed systems. The process with the highest ID always wins the election — hence "bully."

### Election flow

1. Each running process periodically pings the coordinator via a `computar` health check. If the coordinator is unreachable (REST call fails) **or** responds with a negative value (it's stopped), a new election is triggered
2. The initiator groups all higher-ID processes **by server** and sends a single **ELECTION** message per server (not per process). The server-side `Servicio` receives the message and fans it out to all local running processes with ID > initiator
3. Each process that receives the election sends **OK** back to the initiator and starts its own election (if not already in one)
4. The initiator waits for a timeout. If it receives OK, it backs off and waits for a **COORDINATOR** announcement. If no coordinator arrives within a second timeout, it restarts the election
5. If no one responds with OK, the initiator declares itself **COORDINATOR** and broadcasts to all servers (one message per server, fan-out on each)

Additionally, when a process is started (transitions from `stopped` to `running`), it immediately triggers an election to discover or establish the current coordinator. The coordinator itself skips pinging — if `idCoord == id`, the health check returns immediately.

```
Process 1 ──► ping Process 4 (coordinator)   ✗ no response / returns -1
         └──► triggers ELECTION
              sends 1 msg per server (server fan-out to local processes)
              ┌─ Process 2 ──► OK to 1, starts own election
              └─ Process 3 ──► OK to 1, starts own election
              Process 3 ──► ELECTION to higher IDs (none respond)
              Process 3 ──► COORDINATOR broadcast (1 msg/server)
              All processes set coordinator = 3
```

## Architecture

The system has three main components distributed across multiple machines:

```
┌─────────────────────────────────────────────────────────┐
│                    Gestor (CLI Client)                   │
│         Local machine — manages all processes            │
│    start / stop / status / results via REST calls        │
└──────────────┬──────────────┬──────────────┬────────────┘
               │              │              │
          REST API       REST API       REST API
               │              │              │
┌──────────────▼──┐ ┌────────▼───────┐ ┌───▼──────────────┐
│   Server A      │ │   Server B     │ │   Server C       │
│   Tomcat + WAR  │ │   Tomcat + WAR │ │   Tomcat + WAR   │
│                 │ │                │ │                   │
│  ┌───────────┐  │ │  ┌───────────┐ │ │  ┌───────────┐   │
│  │ Process 1 │  │ │  │ Process 2 │ │ │  │ Process 3 │   │
│  │ Process 4 │  │ │  │ Process 5 │ │ │  │ Process 6 │   │
│  └───────────┘  │ │  └───────────┘ │ │  └───────────┘   │
└─────────────────┘ └────────────────┘ └──────────────────┘
        ▲                   ▲                   ▲
        └───────── REST ────┴───── REST ────────┘
              (election / ok / coordinator)
```

Processes are distributed across servers in **round-robin** fashion. Inter-process communication is **server-mediated**: instead of sending N messages to N individual processes, the system sends one REST call per server. The `Servicio` singleton on each server then relays the message to all relevant local processes. This reduces network overhead from O(n) to O(servers).

### Components

| Component | File | Role |
|-----------|------|------|
| **Servicio** | `src/services/Servicio.java` | JAX-RS REST service (Singleton). Exposes endpoints for process lifecycle and election protocol. Receives one message per server and fans out to relevant local processes |
| **Proceso** | `src/cliente/Proceso.java` | Thread implementing the Bully algorithm. Handles elections, coordinator monitoring with dual failure detection (unreachable + negative response), and state transitions using monitors and semaphores |
| **Gestor** | `src/cliente/Gestor.java` | CLI client that creates processes across servers, distributes them round-robin, and provides interactive management (start/stop/status) |

### REST Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/rest/servicio/start` | GET | Create a new process on the server |
| `/rest/servicio/arranque?id=N` | GET | Start (wake up) process N — triggers immediate election |
| `/rest/servicio/parada?id=N` | GET | Stop process N |
| `/rest/servicio/estado` | GET | Get status of all processes on this server |
| `/rest/servicio/resultado` | GET | Get election results from this server |
| `/rest/servicio/eleccion?fromId=N` | GET | Receive election — fans out to all local processes with ID > N |
| `/rest/servicio/recibirOk?fromId=N` | GET | Deliver OK response to process N |
| `/rest/servicio/coordinador?id=N` | GET | Receive coordinator announcement — fans out to all local running processes with ID < N |
| `/rest/servicio/computar?id=N` | GET | Health check — returns 1 if alive, -1 if stopped |

### Process States

Each process tracks two independent state machines:

**Process state:** `stopped` ↔ `running`

**Election state:** `nada` → `eleccion_activa` → `eleccion_pasiva` → `acuerdo`

- **nada** — No election in progress (also set when process is stopped)
- **eleccion_activa** — This process initiated an election, waiting for responses
- **eleccion_pasiva** — Received OK from a higher process, backed off, waiting for coordinator announcement
- **acuerdo** — Coordinator established, normal operation (periodic health checks)

Transition from `eleccion_pasiva`: if no COORDINATOR arrives within the timeout, the process restarts the election instead of staying passive indefinitely.

## Deployment

The included `run.sh` script automates the full deployment pipeline:

1. **SSH key setup** — Downloads and runs `shareKeys.sh` for passwordless SSH
2. **WAR distribution** — Copies the WAR file to each server via SCP
3. **Tomcat installation** — Installs Tomcat 9 on servers that don't have it
4. **WAR deployment** — Deploys the application to each Tomcat instance
5. **Startup** — Starts all Tomcat instances
6. **Gestor launch** — Extracts the client JAR locally and launches the CLI manager

### Configuration

`config.txt` defines the cluster topology:

```
172.20.7.105:8080,172.20.7.70:8080,172.20.7.129:8080
6
```

- **Line 1:** Comma-separated list of server addresses (IP:port)
- **Line 2:** Total number of processes to distribute across all servers (round-robin)

### Running it

```bash
# Deploy to all servers and start the manager
./run.sh
```

The Gestor CLI provides these commands:

| Key | Action |
|-----|--------|
| `A` | Start a process (prompts for ID) |
| `B` | Stop a process (prompts for ID) |
| `C` | Query election results from all servers |
| `D` | Show status of all processes |
| `E` | Help |
| `S` | Exit |

### Requirements

- Java 8+
- Apache Tomcat 9 (auto-installed by `run.sh`)
- SSH access to target servers
- Jersey/JAX-RS libraries (included in `WebContent/WEB-INF/lib/`)

## Concurrency

The implementation uses several synchronization mechanisms:

- **Process monitor** (`synchronized` + `wait`/`notify`) — Stopped processes block on `monitor.wait()` and are woken up by `arrancar()` via `monitor.notify()`, immediately triggering an election
- **Election monitor** — A dedicated monitor for election timeouts: processes call `monitorEleccion.wait(REQUEST_TIMEOUT_MS)` and are notified when OK or COORDINATOR messages arrive via `monitorEleccion.notify()`
- **Log semaphore** — A shared `Semaphore` ensures thread-safe file logging across all processes writing to the same log file
- **Election guard** (`estaEnEleccion`) — Boolean flag preventing re-entrant elections when a process is already participating in one
- **Async REST** — All inter-process communication uses Jersey's `InvocationCallback` for non-blocking requests, with failure callbacks that automatically trigger new elections

## Tech stack

| | |
|---|---|
| **Language** | Java |
| **API** | JAX-RS (Jakarta RESTful Web Services) |
| **Framework** | Jersey 2.x (reference implementation) |
| **Server** | Apache Tomcat 9 |
| **Deployment** | Bash + SSH/SCP |
| **DI** | HK2 (via Jersey) |

## Context

Built for the **Sistemas Distribuidos (SSDD)** course at Universidad de Salamanca, 2025. The project implements the Bully Election Algorithm in a real multi-server environment, covering distributed coordination, fault detection, leader election, and automated deployment.
