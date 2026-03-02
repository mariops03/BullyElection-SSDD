package services;

import java.util.ArrayList;
import java.util.HashMap;

import java.util.Map;

import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import cliente.Proceso;

@Path("servicio")
@Singleton
public class Servicio{
	private static ArrayList<Proceso> procesos = new ArrayList<Proceso>();

    private static final String LOG_FILE = "/home/i9839758/Escritorio/servicios.log";
	private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	
	ArrayList<String> arrayIpHosts = new ArrayList<String>();
	
	static {
	    try {
	        File logFile = new File(LOG_FILE);
	        if (!logFile.exists()) {
	            logFile.createNewFile();
	        } else {
	            try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
	                LocalDateTime now = LocalDateTime.now();
	                fw.write("\n\n--- NUEVA SESIÓN INICIADA: " + now.format(formatter) + " ---\n\n");
	            }
	        }
	    } catch (IOException e) {
	        logError("Error inicializando archivo de log: " + e.getMessage());
	    }
	}
	
	// Método para escribir en el log
	private void logMessage(String message) {
	    String timestamp = LocalDateTime.now().format(formatter);
	    String formattedMsg = String.format("[%s] %s", timestamp, message);
	    System.out.println(formattedMsg);
	    
	    try {
	        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
	            fw.write(formattedMsg + "\n");
	        }
	    } catch (IOException e) {
	        System.err.println("Error escribiendo en log: " + e.getMessage());
	    }
	}
	
	// Método para escribir errores en el log
	private static void logError(String message) {
	    String timestamp = LocalDateTime.now().format(formatter);
	    String formattedMsg = String.format("[%s] ERROR: %s", timestamp, message);
	    System.err.println(formattedMsg);
	    
	    try {
	        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
	            fw.write(formattedMsg + "\n");
	        }
	    } catch (IOException e) {
	        System.err.println("Error escribiendo en log: " + e.getMessage());
	    }
	}
	
	// Método para parsear el mapa de procesos-servidores
	private Map<Integer, String> parsearMapa(String mapaSerializado) {
    Map<Integer, String> mapa = new HashMap<>();
    
    if (mapaSerializado != null && !mapaSerializado.isEmpty()) {
        // Dividir primero por comas para obtener cada entrada
        String[] entries = mapaSerializado.split(",");
        for (String entry : entries) {
            // Para cada entrada, dividir solo en el primer dos puntos
            int primerDosPuntos = entry.indexOf(':');
            if (primerDosPuntos > 0) {
                try {
                    int idProceso = Integer.parseInt(entry.substring(0, primerDosPuntos));
                    String servidor = entry.substring(primerDosPuntos + 1);
                    mapa.put(idProceso, servidor);
                } catch (NumberFormatException e) {
                    logError("Error al parsear ID de proceso: " + entry);
                }
            }
        }
    }
    
    return mapa;
}
	
	@Path("start")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String start(
	    @QueryParam("id") int id, 
	    @QueryParam("ip") String ipHost, 
	    @QueryParam("direcciones") String direccionesParam,
	    @QueryParam("mapa") String mapaSerializado) {
	        
	    logMessage("[START] Process: " + id);
	    	    
	    // Parsear la lista de direcciones
	    arrayIpHosts.clear(); // Limpiar lista existente
	    if (direccionesParam != null && !direccionesParam.isEmpty()) {
	        String[] direcciones = direccionesParam.split(",");
	        for (String direccion : direcciones) {
	            arrayIpHosts.add(direccion);
	        }
	    }
	    
	    // Parsear el mapa de procesos a servidores
	    Map<Integer, String> mapaProcesosServidores = parsearMapa(mapaSerializado);
	    logMessage("[START] Mapa parseado: " + mapaProcesosServidores);
	    
	    // Crear el proceso
	    final Proceso nuevoProceso = new Proceso(id, arrayIpHosts);
	    nuevoProceso.setMapaProcesosServidores(mapaProcesosServidores);
	    procesos.add(nuevoProceso);
	    
	    nuevoProceso.start();
	    
	    return "Proceso " + id + " creado";
	}
	
	@Path("arranque")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String arrancar(@QueryParam(value="id") int id) {
		// Buscar el proceso con el ID especificado
		for (Proceso proceso : procesos) {
			if (proceso.getId() == id) {
				proceso.arrancar();
				return "Proceso " + id + " arrancado";
			}
		}
		return "Error: No se encontró el proceso con ID " + id;
	}
	
	@Path("coordinador")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public void coordinador(@QueryParam(value="id") int id) {
	    for (Proceso proceso : procesos) {
	        if (proceso.getEstadoProceso() == Proceso.tiposEstadoProceso.running && proceso.getId() < id) {
	            proceso.recibirCoordinador(id);
	        }
	    }
	}
	
	@Path("parada")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String parar(@QueryParam(value="id") int id) {
		try {
			// Buscar proceso por ID
			for (Proceso proceso : procesos) {
				if (proceso.getId() == id) {
					proceso.parar();
					return "Proceso " + id + " marcado para parar";
				}
			}
			return "Error: No se encontró el proceso con ID " + id;
		} catch (Exception e) {
			return "Error al parar proceso " + id + ": " + e.getMessage();
		}
	}

	@Path("estado")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String estado() {
		StringBuilder result = new StringBuilder();
		result.append("\n--- ESTADO DE PROCESOS ---\n");
		result.append(String.format("%-12s %-15s %-20s %-20s\n", 
					"ID PROCESO", "ID COORDINADOR", "ESTADO DEL PROCESO", "ESTADO DE ELECCIÓN"));
		result.append("-----------------------------------------------------------------------\n");
		
		for (Proceso proceso : procesos) {
			result.append(String.format("%-12d %-15d %-20s %-20s\n", 
						proceso.getId(),
						proceso.getIdCoord(),
						proceso.getEstadoProceso(),
						proceso.getEstadoEleccion()));
		}
		
		return result.toString();
	}

	@Path("eleccion")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public void eleccion(@QueryParam("fromId") int fromId) {
		try {
			// Encontrar procesos con ID mayor que respondan OK
			for (Proceso proceso : procesos) { 
				// Solo procesos RUNNING con ID mayor deben responder
				if (proceso.getId() > fromId && //Filtra los procesos con ID mayor
					proceso.getEstadoProceso() == Proceso.tiposEstadoProceso.running) {
						logMessage("Proceso " + proceso.getId() + " ha recibido elección de proceso " + fromId);
						proceso.ok(fromId);
				}
			}					
		} catch (Exception e) {
			logError("Error procesando elección: " + e.getMessage());
		}
	}
	@Path("recibirOk")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public void recibirOk(@QueryParam("fromId") int fromId) {

		for (Proceso proceso : procesos) {
			if (proceso.getId() == fromId) {
				proceso.recibirOk();
				break;
			}
		}
	}

	@Path("computar")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public int computar(@QueryParam("id") int id) {
		
		// Buscar proceso por ID
		for (Proceso proceso : procesos) {
			if (proceso.getId() == id) {
				return proceso.computar();
			}
		}
		
		logError("Error: No se encontró el proceso con ID " + id);
		return -1;
	}

	@Path("resultado")
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String resultado() {
		StringBuilder result = new StringBuilder();
		result.append("Resultados de los procesos:\n");
		
		for (Proceso proceso : procesos) {
			result.append("Proceso ").append(proceso.getId())
				.append(" - Coordinador: ").append(proceso.getIdCoord())
				.append(" - Estado: ").append(proceso.getEstadoProceso())
				.append("\n");
		}
		
		return result.toString();
	}
}