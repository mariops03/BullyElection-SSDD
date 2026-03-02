# Bully Election Algorithm

Distributed implementation of the [Bully Election Algorithm](https://en.wikipedia.org/wiki/Bully_algorithm) across multiple servers. Processes communicate via REST APIs, elect a coordinator through the Bully protocol, and automatically trigger new elections when the coordinator fails. The system deploys as a WAR on Apache Tomcat instances and includes a CLI manager to control processes in real time.

Built as a university project for the **Sistemas Distribuidos (SSDD)** course.

## How it works

The [Bully Algorithm](https://en.wikipedia.org/wiki/Bully_algorithm) (Garcia-Molina, 1982) is a leader election protocol for distributed systems. The process with the highest ID always wins the election — hence "bully."

**Election flow:**

1. A process detects the coordinator is unresponsive (via periodic `computar` health checks)
2. It sends an **ELECTION** message to all processes with higher IDs
3. If any higher process responds with **OK**, the initiator backs off and waits
4. The highest responding process repeats the election upward
5. If no one responds, the initiator declares itself **COORDINATOR** and broadcasts to all

**Coordinator monitoring:**

Each running process periodically pings the current coordinator. If the coordinator doesn't respond (crashed or stopped), a new election is triggered automatically.

```
Process 1 ──► Process 4 (coordinator)   ✗ no response
         └──► triggers ELECTION
              Process 2 ──► OK to 1
              Process 3 ──► OK to 1
              Process 3 ──► ELECTION to higher
              (no response)
              Process 3 ──► COORDINATOR broadcast
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

Processes are distributed across servers in **round-robin** fashion. All inter-process communication happens through async REST calls (Jersey client), meaning processes on different physical machines communicate seamlessly.

### Components

| Component | File | Role |
|-----------|------|------|
| **Servicio** | `src/services/Servicio.java` | JAX-RS REST service (Singleton). Exposes endpoints for process lifecycle and election protocol. Deployed as WAR on each Tomcat instance |
| **Proceso** | `src/cliente/Proceso.java` | Thread implementing the Bully algorithm. Handles elections, coordinator monitoring, and state transitions using monitors and semaphores |
| **Gestor** | `src/cliente/Gestor.java` | CLI client that creates processes across servers, distributes them round-robin, and provides interactive management (start/stop/status) |

### REST Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/rest/servicio/start` | GET | Create a new process on the server |
| `/rest/servicio/arranque?id=N` | GET | Start (wake up) process N |
| `/rest/servicio/parada?id=N` | GET | Stop process N |
| `/rest/servicio/estado` | GET | Get status of all processes on this server |
| `/rest/servicio/resultado` | GET | Get election results from this server |
| `/rest/servicio/eleccion?fromId=N` | GET | Receive an election message from process N |
| `/rest/servicio/recibirOk?fromId=N` | GET | Receive an OK response |
| `/rest/servicio/coordinador?id=N` | GET | Receive coordinator announcement |
| `/rest/servicio/computar?id=N` | GET | Health check — is process N alive? |

### Process States

Each process tracks two independent state machines:

**Process state:** `stopped` ↔ `running`

**Election state:** `nada` → `eleccion_activa` → `eleccion_pasiva` → `acuerdo`

- **nada** — No election in progress
- **eleccion_activa** — This process initiated an election
- **eleccion_pasiva** — Received OK from a higher process, waiting for coordinator
- **acuerdo** — Coordinator established, normal operation

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
- **Line 2:** Total number of processes to distribute across all servers

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

- **Monitors** (`synchronized` + `wait`/`notify`) — Process lifecycle: stopped processes wait on a monitor and are woken up on `arrancar()`
- **Semaphores** — Log file access: a shared `Semaphore` ensures thread-safe logging across all processes
- **Election monitor** — A dedicated monitor for election timeouts: processes `wait(timeout)` for OK/coordinator messages and are notified when they arrive
- **Async REST** — All inter-process communication uses Jersey's `InvocationCallback` for non-blocking requests

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
