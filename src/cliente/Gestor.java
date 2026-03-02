package cliente;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.jersey.client.ClientConfig;

//import utils.Config

public class Gestor {

    private static int NUM_PROCESOS = 6; // Número de procesos a crear por servidor (valor predeterminado)
    private static final String CONFIG_FILE = "./config.txt";
    public static Client client;

    /** Map<idProceso, host:puerto> */
    static Map<Integer, String> procesosServidor; 
    /** Map<host:puerto, WebTarget raíz> */
    static Map<String, WebTarget> servidores; 
    /** Map<host:puerto, listaIdsProceso> */
    static Map<String, List<Integer>> servidorProcesos;

    public static void ejecutar() {
        Scanner scanner = new Scanner(System.in);
        boolean salir = false;

        imprimirAyuda();

        while (!salir) {
            System.out.print("Gestor> ");
            String comando = scanner.nextLine().trim();

            if (comando.isEmpty()) {
                continue;
            }

            switch (comando.toLowerCase()) {
                case "a":
                    arrancarProceso(scanner);
                    break;

                case "b":
                    pararProceso(scanner);
                    break;

                case "c":
                    resultado();
                    break;

                case "d":
                    mostrarEstadoProcesos();
                    break;

                case "e":
                    imprimirAyuda();
                    break;

                case "s":
                    salir = true;
                    break;

                default:
                    System.out.println("Comando desconocido. Escribe 'E' para ver la lista de comandos.");
                    break;
            }
        }

        scanner.close();
        System.out.println("Gestor finalizado.");
    }

    private static void arrancarProceso(Scanner scanner) {
        try {
            System.out.print("Proceso: ");
            int idProceso = Integer.parseInt(scanner.nextLine().trim());

            String servidor = procesosServidor.getOrDefault(idProceso, "");
            if (servidor.isEmpty()) {
                System.out.println("Error: El proceso " + idProceso + " no está registrado.");
                return;
            }
            WebTarget target = servidores.get(servidor);

            WebTarget requestTarget = target.path("rest").path("servicio").path("arranque")
                                            .queryParam("id", idProceso);

            System.out.println("Arrancando proceso " + idProceso + " en servidor " + servidor);
            System.out.println(requestTarget.getUri());

            requestTarget.request(MediaType.TEXT_PLAIN)
                         .async()
                         .get(new InvocationCallback<String>() {
                             @Override public void completed(String respuesta) {
                                 System.out.println("[OK] " + respuesta);
                             }
                             @Override public void failed(Throwable t) {
                                 System.out.println("[ERROR] al arrancar proceso " + idProceso + ": " + t.getMessage());
                                 t.printStackTrace();
                             }
                         });

        } catch (NumberFormatException e) {
            System.out.println("Error: Por favor ingrese un número válido.");
        }
    }

    private static void pararProceso(Scanner scanner) {
        try {
            System.out.print("Proceso: ");
            int idProceso = Integer.parseInt(scanner.nextLine().trim());

            String servidor = procesosServidor.getOrDefault(idProceso, "");
            if (servidor.isEmpty()) {
                System.out.println("Error: El proceso " + idProceso + " no está registrado.");
                return;
            }
            WebTarget target = servidores.get(servidor);

            WebTarget requestTarget = target.path("rest").path("servicio").path("parada")
                                            .queryParam("id", idProceso);

            System.out.println("Parando proceso " + idProceso + " en servidor " + servidor);
            System.out.println(requestTarget.getUri());

            requestTarget.request(MediaType.TEXT_PLAIN)
                         .async()
                         .get(new InvocationCallback<String>() {
                             @Override public void completed(String respuesta) {
                                 System.out.println("[OK] " + respuesta);
                             }
                             @Override public void failed(Throwable t) {
                                 System.out.println("[ERROR] al parar proceso " + idProceso + ": " + t.getMessage());
                             }
                         });

        } catch (NumberFormatException e) {
            System.out.println("Error: Por favor ingrese un número válido.");
        }
    }

    private static void resultado() {
        System.out.println("Consultando resultados (modo asíncrono)...");
        servidores.forEach((servidor, webTarget) -> {
            WebTarget req = webTarget.path("rest").path("servicio").path("resultado");
            req.request(MediaType.TEXT_PLAIN)
               .async()
               .get(new InvocationCallback<String>() {
                   @Override public void completed(String respuesta) {
                       System.out.println("Resultados del servidor " + servidor + ":\n" + respuesta);
                   }
                   @Override public void failed(Throwable t) {
                       System.out.println("Error al obtener resultados del servidor " + servidor + ": " + t.getMessage());
                   }
               });
        });
    }

    private static void mostrarEstadoProcesos() {
        System.out.println("=== ESTADO DE TODOS LOS PROCESOS (async) ===");
        servidores.forEach((servidor, webTarget) -> {
            ClientConfig cfg = new ClientConfig()
                    .property("jersey.config.client.connectTimeout", 5000)
                    .property("jersey.config.client.readTimeout", 10000);

            WebTarget req = ClientBuilder.newClient(cfg)
                                         .target(webTarget.getUri())
                                         .path("rest").path("servicio").path("estado");

            req.request(MediaType.TEXT_PLAIN)
               .async()
               .get(new InvocationCallback<String>() {
                   @Override public void completed(String estado) {
                       System.out.println("\nProcesos en servidor " + servidor + ":\n" + estado);
                   }
                   @Override public void failed(Throwable t) {
                       System.out.println("El servidor " + servidor + " no responde o tardó demasiado: " + t.getMessage());
                   }
               });
        });
    }

    public static void imprimirAyuda() {
        System.out.println("(A) Arranque, (B) Parar, (C) Resultado, (D) Estado, (E) Ayuda, (S) Salir");
    }

    private static String serializarMapaProcesosServidor() {
        StringBuilder sb = new StringBuilder();
        procesosServidor.forEach((id, srv) -> sb.append(id).append(":").append(srv).append(","));
        if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* -----------------------------  MAIN  ----------------------------- */
    /* ------------------------------------------------------------------ */

    public static void main(String[] args) {
        client = ClientBuilder.newClient();

        // Inicializar estructuras
        procesosServidor = new HashMap<>();
        servidores = new HashMap<>();
        servidorProcesos = new HashMap<>();

        // Leer configuración desde archivo
        List<String> servidoresList = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
            // Primera línea: servidores separados por coma
            String servidoresLine = reader.readLine();
            if (servidoresLine != null && !servidoresLine.trim().isEmpty()) {
                String[] servidoresArray = servidoresLine.split(",");
                for (String servidor : servidoresArray) {
                    servidoresList.add(servidor.trim());
                }
            } else {
                System.out.println("Advertencia: No se encontraron servidores en el archivo. Usando valores predeterminados.");
            }
            
            // Segunda línea: número de procesos
            String numProcesosLine = reader.readLine();
            if (numProcesosLine != null && !numProcesosLine.trim().isEmpty()) {
                try {
                    NUM_PROCESOS = Integer.parseInt(numProcesosLine.trim());
                } catch (NumberFormatException e) {
                    System.out.println("Advertencia: Número de procesos inválido. Usando valor predeterminado: " + NUM_PROCESOS);
                }
            }
        } catch (IOException e) {
            System.out.println("Error al leer el archivo de configuración: " + e.getMessage());
        }

        System.out.println("Servidores configurados: " + servidoresList);
        System.out.println("Número de procesos: " + NUM_PROCESOS);

        // Crear WebTargets para cada servidor
        for (String servidor : servidoresList) {
            try {
                URI uri = UriBuilder.fromUri("http://" + servidor + "/Distri").build();
                WebTarget target = client.target(uri);
                servidores.put(servidor, target);
                servidorProcesos.put(servidor, new ArrayList<>());
            } catch (Exception e) {
                System.out.println("Error creando WebTarget para servidor " + servidor + ": " + e.getMessage());
            }
        }

        // Información completa de hosts para arrancar procesos
        List<String> direccionesCompletas = new ArrayList<>(servidoresList);

        // Distribuir procesos de forma round‑robin
        for (int i = 1; i <= NUM_PROCESOS; i++) {
            String servidorActual = servidoresList.get((i - 1) % servidoresList.size());
            procesosServidor.put(i, servidorActual);
            servidorProcesos.get(servidorActual).add(i);
        }

        String mapaSerializado = serializarMapaProcesosServidor();
        System.out.println("Mapa de procesos serializado: " + mapaSerializado);

        // Crear procesos en servidores
        for (int i = 1; i <= NUM_PROCESOS; i++) {
            String servidorActual = procesosServidor.get(i);
            WebTarget targetActual = servidores.get(servidorActual);
            try {
                String direccionesParam = String.join(",", direccionesCompletas);
                String response = targetActual.path("rest").path("servicio").path("start")
                        .queryParam("id", i)
                        .queryParam("ip", servidorActual.split(":" )[0])
                        .queryParam("direcciones", direccionesParam)
                        .queryParam("mapa", mapaSerializado)
                        .request(MediaType.TEXT_PLAIN)
                        .get(String.class);

                System.out.println("Proceso " + i + " creado correctamente en " + servidorActual + ": " + response);
            } catch (Exception e) {
                System.out.println("Error al crear el proceso " + i + " en " + servidorActual + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Mostrar distribución
        System.out.println("\n--- DISTRIBUCIÓN DE PROCESOS ---");
        servidorProcesos.forEach((srv, lista) -> System.out.println("Servidor " + srv + ": Procesos " + lista));
        System.out.println("--------------------------------\n");

        ejecutar();
    }
}
