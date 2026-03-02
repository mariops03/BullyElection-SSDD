package cliente;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Proceso extends Thread {

    private static final int REQUEST_TIMEOUT_MS = 1000;

    private int id;
    private int idCoord;
    ArrayList<String> arrayIp;

    /** Almacena la dirección (host:puerto) de cada proceso */
    private Map<Integer, String> mapaProcesosServidores = new HashMap<>();

    private final Object monitor;
    private final Object monitorEleccion;
    Semaphore semaforoCoord;
    private boolean recibioOk = false;
    private boolean estaEnEleccion = false;

    private static final String LOG_FILE = "/home/i9839758/Escritorio/procesos.log";
    private static final Semaphore logSemaphore = new Semaphore(1);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public enum tiposEstadoEleccion { acuerdo, eleccion_activa, eleccion_pasiva, nada }
    public enum tiposEstadoProceso { running, stopped }

    Map<String, Integer> servidoresUnicos = new HashMap<>();

    private tiposEstadoEleccion estadoEleccion;
    private tiposEstadoProceso estadoProceso;

    public Proceso(int id, ArrayList<String> arrayIp) {
        super();
        this.id = id;
        this.arrayIp = arrayIp;
        this.idCoord = -1;
        this.monitor = new Object();
        this.monitorEleccion = new Object();
        this.estadoEleccion = tiposEstadoEleccion.nada;
        this.estadoProceso = tiposEstadoProceso.stopped;
        this.semaforoCoord = new Semaphore(0);
        logMessage("Creado con IPs: " + arrayIp);
    }

    public void setMapaProcesosServidores(Map<Integer, String> mapa) {
        this.mapaProcesosServidores = new HashMap<>(mapa);
        logMessage("Mapa proceso‑servidor configurado: " + mapaProcesosServidores);
    }

    private String getServidorPorProceso(int idProceso) {
        String servidor = mapaProcesosServidores.getOrDefault(idProceso, "");
        if (servidor.isEmpty()) {
            logMessage("ADVERTENCIA: no se encontró servidor para el proceso " + idProceso);
        }
        return servidor;
    }

    private WebTarget crearWebTargetParaProceso(int idProceso) {
        String servidor = getServidorPorProceso(idProceso);
        if (servidor.isEmpty()) {
            logMessage("ERROR: no se puede crear WebTarget para proceso " + idProceso);
            return null;
        }
        try {
            URI uri = UriBuilder.fromUri("http://" + servidor + "/Distri").build();
            Client client = ClientBuilder.newClient();
            return client.target(uri);
        } catch (Exception e) {
            logMessage("ERROR creando WebTarget para proceso " + idProceso + ": " + e.getMessage());
            return null;
        }
    }

    private void logMessage(String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        String formattedMsg = String.format("[%s] Proceso %d: %s", timestamp, id, message);
        System.out.println(formattedMsg);
        try {
            logSemaphore.acquire();
            try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                out.println(formattedMsg);
            } finally {
                logSemaphore.release();
            }
        } catch (InterruptedException | IOException e) {
            System.err.println("Error writing log: " + e.getMessage());
        }
    }

    public void setIdCoord(int idCoord) { 
        int oldCoord = this.idCoord;
        this.idCoord = idCoord;
        if (oldCoord != idCoord) {
            logMessage("Cambio de coordinador: " + oldCoord + " -> " + idCoord);
        }
    }
    
    public void setEstadoEleccion(tiposEstadoEleccion nuevoEstado) {
        setEstadoEleccion(nuevoEstado, "Sin motivo especificado");
    }
    
    public void setEstadoEleccion(tiposEstadoEleccion nuevoEstado, String motivo) {
        tiposEstadoEleccion estadoAnterior = this.estadoEleccion;
        this.estadoEleccion = nuevoEstado;
        if (estadoAnterior != nuevoEstado) {
            logMessage("Cambio de estado de elección: " + estadoAnterior + " -> " + nuevoEstado + " | Motivo: " + motivo);
        }
    }
    
    public void setEstadoProceso(tiposEstadoProceso nuevoEstado) {
        setEstadoProceso(nuevoEstado, "Sin motivo especificado");
    }
    
    public void setEstadoProceso(tiposEstadoProceso nuevoEstado, String motivo) {
        tiposEstadoProceso estadoAnterior = this.estadoProceso;
        this.estadoProceso = nuevoEstado;
        if (estadoAnterior != nuevoEstado) {
            logMessage("Cambio de estado de proceso: " + estadoAnterior + " -> " + nuevoEstado + " | Motivo: " + motivo);
        }
    }
    public long getId() { return id; }
    public int getIdCoord() { return idCoord; }
    public tiposEstadoProceso getEstadoProceso() { return estadoProceso; }
    public tiposEstadoEleccion getEstadoEleccion() { return estadoEleccion; }

    public void run() {
        logMessage("arrancado (thread RUN)");
        Random random = new Random();

        while (true) {
            if (estadoProceso == tiposEstadoProceso.stopped) {
                synchronized (monitor) {
                    try {
                        setIdCoord(-1);
                        setEstadoEleccion(tiposEstadoEleccion.nada, "Proceso detenido");
                        monitor.wait();
                    } catch (InterruptedException e) {
                        logMessage("interrumpido mientras esperaba: " + e.getMessage());
                        Thread.currentThread().interrupt();
                    }
                }
                eleccion();
            } else {
                try {
                    
                    Thread.sleep(random.nextInt(501) + 500); // 0.5‑1s
                    coordinadorComputar();

                } catch (Exception e) {
                    logMessage("Error en bucle principal: " + e.getMessage());
                }
            }
        }
    }

    public int coordinadorComputar() {
        if (estadoProceso == tiposEstadoProceso.stopped) return -1;
        if (estaEnEleccion) return -1;
        if (idCoord <= 0) {
            logMessage("ID de coordinador inválido, iniciando una nueva eleccion...");
            eleccion();
            return -1;
        }
        if (idCoord == id) return 0; //Si el idCoord es el mismo que el id del proceso, no se hace nada (nos ahorramos una llamada al servidor)
        WebTarget target = crearWebTargetParaProceso(idCoord);
        if (target == null) return -3;

        target.path("rest").path("servicio").path("computar")
              .queryParam("id", idCoord)
              .request()
              .async()
              .get(new InvocationCallback<Integer>() {
                  @Override public void completed(Integer valor) {
                      logMessage("Coordinador respondió: " + valor);
                      if (valor < 0) {
                        if(estaEnEleccion){
                            logMessage("Deberia de iniciar eleccion por computar() pero estoy en eleccion");
                        }else{
                            logMessage("Valor negativo → inicio de elección,ya que no tengo ninguna activa");
                            eleccion();
                        }
                      }
                  }
                  @Override public void failed(Throwable t) {
                      logMessage("Error petición computar: " + t.getMessage() + " → inicio de elección");
                      eleccion();
                  }
              });

        return 0;
    }

    public void eleccion() {
        if (estaEnEleccion) return;
        if (estadoProceso == tiposEstadoProceso.stopped) return;
        logMessage("[NEW] elección");
        estaEnEleccion = true;
        setEstadoEleccion(tiposEstadoEleccion.eleccion_activa, "Inicio de elección por proceso " + id);
        
        if (arrayServidores() == -1) {
            logMessage("No hay procesos con ID mayor → me declaro coordinador");
            coordinador(id);
            estaEnEleccion = false;
            return;
        }
                
        // Para cada servidor, enviar mensaje a su proceso representante
        for (Map.Entry<String, Integer> entry : servidoresUnicos.entrySet()) {
            String servidor = entry.getKey();
            int procesoId = entry.getValue();
            
            WebTarget target = crearWebTargetParaProceso(procesoId);
            if (target == null) continue;
            
            logMessage("→ envío ELECCION al servidor '" + servidor + "' vía proceso " + procesoId);
            target.path("rest").path("servicio").path("eleccion")
                .queryParam("fromId", id)
                .request()
                .async()
                .get();
        }
        waitTimeout();
        if (recibioOk) {
            recibioOk = false;
            logMessage("Recibí OK de otro proceso, no soy coordinador");
            waitTimeout(); 

            if(!recibioOk){
                logMessage("No recibí coordinador, inicio elección de nuevo");
                eleccion();
            }else{
                recibioOk = false;
            }

        } else {
            logMessage("No recibí OK, me declaro coordinador");
            coordinador(id);
        }
        estaEnEleccion = false;
    }

    public void coordinador(int idCoord) {
        if (estadoProceso == tiposEstadoProceso.stopped) return;
        logMessage("Proceso " + idCoord + " → coordinador");
        this.setIdCoord(idCoord);
        setEstadoEleccion(tiposEstadoEleccion.acuerdo, "Nuevo coordinador establecido: " + idCoord); 
        Set<String> servidoresNotificados = new HashSet<>();
        
        for (Map.Entry<Integer, String> entry : mapaProcesosServidores.entrySet()) {
            int procesoId = entry.getKey();
            String servidor = entry.getValue();
            
            // Ignorar si es el proceso actual o si ya notificamos a este servidor
            if (procesoId == id || servidoresNotificados.contains(servidor)) continue;
            
            servidoresNotificados.add(servidor);
            
            WebTarget target = crearWebTargetParaProceso(procesoId);
            if (target == null) continue;
            
            target.path("rest").path("servicio").path("coordinador")
                .queryParam("id", idCoord)
                .request(MediaType.TEXT_PLAIN)
                .async()
                .get();
        }
        
    }

    public int computar() {
        if (estadoProceso == tiposEstadoProceso.stopped) return -1;
        try {
            logMessage("[COMPUTAR] en estado " + estadoProceso);
            Thread.sleep(new Random().nextInt(201) + 100);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    public void ok(int fromId) {
        logMessage("[Envio ok] -> " + fromId);
        WebTarget target = crearWebTargetParaProceso(fromId);
        if (target == null) {
            logMessage("ERROR: No se pudo crear target para notificar OK al proceso " + fromId);
            return;
        }
    
        target.path("rest").path("servicio").path("recibirOk")
            .queryParam("fromId",fromId) // Le enviamos el id del que nos ha mandado eleccion()
            .request(MediaType.TEXT_PLAIN)
            .async()
            .get();
        if(!estaEnEleccion) eleccion();
    }

    public void recibirOk(){
    	if(getEstadoEleccion() != tiposEstadoEleccion.eleccion_activa) {
    		return;
    	}
        logMessage("[RECIBO OK]");
        setEstadoEleccion(tiposEstadoEleccion.eleccion_pasiva, "Recibida respuesta positiva de un proceso me pongo en pasiva");
        recibioOk = true;
        synchronized (monitorEleccion) { monitorEleccion.notify(); }
    }

    public void recibirCoordinador(int idCoord) {
        logMessage("[RECIBO COORDINADOR] " + idCoord);
        setIdCoord(idCoord);
        setEstadoEleccion(tiposEstadoEleccion.acuerdo, "Recibido coordinador: " + idCoord);
        recibioOk = true;
        synchronized (monitorEleccion) { monitorEleccion.notify(); }
    }

    public void arrancar() {
        reseteo();
        setEstadoProceso(tiposEstadoProceso.running, "Solicitud de arranque manual");
        synchronized (monitor) { monitor.notify(); }
        logMessage("[ARRANCADO]");
    }

    public void parar() {
        reseteo();
        setEstadoProceso(tiposEstadoProceso.stopped, "Solicitud de parada manual");
        setEstadoEleccion(tiposEstadoEleccion.nada, "Proceso detenido manualmente");
        logMessage("[STOPPED]");
    }

    public void reseteo(){
        setIdCoord(-1);
        recibioOk = false;
        estaEnEleccion = false;
    }
    public int arrayServidores(){
        servidoresUnicos.clear();
        for (Map.Entry<Integer, String> entry : mapaProcesosServidores.entrySet()) {
            int procesoId = entry.getKey();
            String servidor = entry.getValue();
            // Solo considerar procesos con ID mayor
            if (procesoId > id) {
                // Si es el primer proceso de este servidor o tiene ID menor que el coordinador
                if (!servidoresUnicos.containsKey(servidor) || 
                    procesoId < servidoresUnicos.get(servidor)) {
                    servidoresUnicos.put(servidor, procesoId);
                }
            }
        }
        if(servidoresUnicos.isEmpty()){
            return -1;
        }
        return 0;
    }
    public void waitTimeout(){
        synchronized (monitorEleccion) {
            try {
                logMessage("TIMEOUT...");
                monitorEleccion.wait(REQUEST_TIMEOUT_MS);
            } catch (InterruptedException e) {
                logMessage("Interrumpido mientras esperaba: " + e.getMessage());
                eleccion();
                Thread.currentThread().interrupt();
            }
        }
    }
}