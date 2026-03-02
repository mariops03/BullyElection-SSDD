#!/bin/bash
####################################################################################
#  Script de despliegue y arranque Bully          				   #
#  Daniel Mulas Fajardo | Mario Prieta Sanchez                   	           #
####################################################################################

# --- Configuración General ---
REMOTE_USER="i9839758"
REMOTE_WAR_FILE="Distri.war" # Archivo WAR para despliegue remoto
LOCAL_CLIENT_JAR="Distri.jar" # Archivo JAR para ejecución local
REMOTE_BASE_DIR="Escritorio"
REMOTE_APP_SUBDIR="bully-app"
REMOTE_TOMCAT_SUBDIR="tomcat"

# Ruta al archivo de configuración de IPs
SCRIPT_DIR="$(dirname "$0")"
CONFIG_FILE="$SCRIPT_DIR/config.txt"

config_keys() {
    echo "INFO: Descargando y configurando shareKeys.sh..."
    if ! wget http://vis.usal.es/rodrigo/documentos/sisdis/scripts/shareKeys.sh -O "$SCRIPT_DIR/shareKeys.sh"; then
        echo "Error: No se pudo descargar shareKeys.sh. Saliendo."
        exit 1
    fi
    chmod +x "$SCRIPT_DIR/shareKeys.sh"

    if [ ${#HOST_ENTRIES_ARRAY[@]} -eq 0 ]; then
        echo "Error: No hay hosts definidos para configurar con shareKeys.sh."
        return 1
    fi

    echo "INFO: Ejecutando shareKeys.sh para cada host..."
    for host_entry_for_keys in "${HOST_ENTRIES_ARRAY[@]}"; do
        local current_host_ip="${host_entry_for_keys%%:*}"
        if [ -z "$current_host_ip" ]; then
            echo "Advertencia: Entrada vacía o mal formada encontrada en config.txt ('$host_entry_for_keys') al configurar claves. Saltando."
            continue
        fi
        echo "INFO: Configurando claves para $REMOTE_USER@$current_host_ip usando shareKeys.sh..."
        if ! "$SCRIPT_DIR/shareKeys.sh" "$REMOTE_USER@$current_host_ip"; then
            echo "Advertencia: shareKeys.sh reportó un error o falló para $REMOTE_USER@$current_host_ip."
        else
            echo "INFO: shareKeys.sh ejecutado para $REMOTE_USER@$current_host_ip."
        fi
    done
    echo "INFO: Proceso de configuración de claves con shareKeys.sh finalizado."
}

# Verificar archivos locales necesarios
if [ ! -f "$REMOTE_WAR_FILE" ]; then
    echo "Error: El archivo WAR local para despliegue remoto no existe: $REMOTE_WAR_FILE"
    exit 1
fi

if [ ! -f "$LOCAL_CLIENT_JAR" ]; then
    echo "Error: El archivo JAR local para ejecución del Gestor no existe: $LOCAL_CLIENT_JAR"
    exit 1
fi

# Verificar y leer archivo de configuración de IPs
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: El archivo de configuración de IPs no existe en el directorio del script: $CONFIG_FILE"
    exit 1
fi

HOST_ENTRIES_STRING=$(head -n 1 "$CONFIG_FILE")
if [ -z "$HOST_ENTRIES_STRING" ]; then
    echo "Error: El archivo de configuración '$CONFIG_FILE' está vacío o la primera línea no contiene IPs."
    exit 1
fi

# Convertir la cadena de hosts en un array
IFS=',' read -r -a HOST_ENTRIES_ARRAY <<< "$HOST_ENTRIES_STRING"

config_keys

echo "[!] Iniciando despliegue en hosts definidos en $CONFIG_FILE: $HOST_ENTRIES_STRING"

# Bucle para cada IP leída del archivo
for host_entry in "${HOST_ENTRIES_ARRAY[@]}"; do
    host_ip="${host_entry%%:*}"

    if [ -z "$host_ip" ]; then
        echo "Advertencia: Entrada vacía o mal formada."
        continue
    fi

    echo ""
    echo "================== Procesando Host: $host_ip =================="

    REMOTE_APP_PATH="/home/$REMOTE_USER/$REMOTE_BASE_DIR/$REMOTE_APP_SUBDIR"
    REMOTE_TOMCAT_PATH="/home/$REMOTE_USER/$REMOTE_BASE_DIR/$REMOTE_TOMCAT_SUBDIR"
    APP_NAME=$(basename "$REMOTE_WAR_FILE" .war)

    # 1. Conectar y Crear Directorios
    echo "INFO: Creando directorios remotos en $host_ip..."
    if ! ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$host_ip "mkdir -p \"$REMOTE_APP_PATH\" && mkdir -p \"$REMOTE_TOMCAT_PATH\""; then
        echo "Error: Conexión o creación de directorios fallida en $host_ip. Saltando."
        continue
    fi

    # 2. Copiar WAR (para despliegue remoto)
    echo "INFO: Copiando $REMOTE_WAR_FILE a $host_ip:$REMOTE_APP_PATH/ ..."
    if ! scp -q -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "$REMOTE_WAR_FILE" $REMOTE_USER@$host_ip:"$REMOTE_APP_PATH/"; then
        echo "Error: SCP fallido en $host_ip. Saltando."
        continue
    fi

    # 3. Instalar/Verificar Tomcat Remoto
    echo "INFO: Verificando/Instalando Tomcat en $host_ip..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$host_ip << EOF_INSTALL > /dev/null 2>&1
# Las variables con '\$' se expandirán en el servidor remoto
TOMCAT_INSTALL_DIR="$REMOTE_TOMCAT_PATH"
if [ ! -f "\$TOMCAT_INSTALL_DIR/bin/startup.sh" ]; then
    echo "INFO_REMOTE: Tomcat no encontrado en \$TOMCAT_INSTALL_DIR, intentando instalar..." >&2
    TMP_DOWNLOAD_DIR="/tmp/tomcat_download_\$\$"
    mkdir -p \$TMP_DOWNLOAD_DIR && cd \$TMP_DOWNLOAD_DIR || { echo "ERROR_REMOTE: Fallo al crear dir temporal" >&2; exit 1; }

    TOMCAT_URL_LATEST='https://dlcdn.apache.org/tomcat/tomcat-9/v9.0.104/bin/apache-tomcat-9.0.104.tar.gz'
    TOMCAT_URL_FALLBACK1='https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.102/bin/apache-tomcat-9.0.102.tar.gz'
    TOMCAT_URL_FALLBACK2='https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.80/bin/apache-tomcat-9.0.80.tar.gz'

    echo "INFO_REMOTE: Descargando Tomcat..." >&2
    wget -q --timeout=60 --tries=2 \$TOMCAT_URL_LATEST -O tomcat.tar.gz || \
    wget -q --timeout=60 --tries=2 \$TOMCAT_URL_FALLBACK1 -O tomcat.tar.gz || \
    wget -q --timeout=60 --tries=2 \$TOMCAT_URL_FALLBACK2 -O tomcat.tar.gz

    if [ ! -f "tomcat.tar.gz" ] || [ ! -s "tomcat.tar.gz" ]; then
        echo "ERROR_REMOTE: Fallo al descargar Tomcat de todas las URLs." >&2
        rm -rf \$TMP_DOWNLOAD_DIR
        exit 1
    fi

    echo "INFO_REMOTE: Descomprimiendo Tomcat..." >&2
    tar -xzf tomcat.tar.gz || { echo "ERROR_REMOTE: Fallo al descomprimir tomcat.tar.gz" >&2; rm -rf \$TMP_DOWNLOAD_DIR; exit 1; }

    EXTRACTED_DIR=\$(find . -maxdepth 1 -type d -name 'apache-tomcat-9.0.*' -print -quit)
    if [ -z "\$EXTRACTED_DIR" ]; then
        echo "ERROR_REMOTE: No se encontró el directorio extraído de Tomcat." >&2
        rm -rf \$TMP_DOWNLOAD_DIR
        exit 1
    fi

    echo "INFO_REMOTE: Moviendo Tomcat a \$TOMCAT_INSTALL_DIR..." >&2
    mkdir -p \$TOMCAT_INSTALL_DIR
    mv \$EXTRACTED_DIR/* \$TOMCAT_INSTALL_DIR/ || { echo "ERROR_REMOTE: Fallo al mover archivos de Tomcat." >&2; rm -rf \$TMP_DOWNLOAD_DIR; exit 1; }

    chmod +x \$TOMCAT_INSTALL_DIR/bin/*.sh
    rm -rf \$TMP_DOWNLOAD_DIR
    echo "INFO_REMOTE: Tomcat instalado en \$TOMCAT_INSTALL_DIR." >&2
    if [ ! -f "\$TOMCAT_INSTALL_DIR/bin/startup.sh" ]; then
        echo "ERROR_REMOTE: startup.sh no encontrado después de la instalación." >&2
        exit 1
    fi
else
    echo "INFO_REMOTE: Tomcat ya parece estar instalado en \$TOMCAT_INSTALL_DIR." >&2
fi
exit 0
EOF_INSTALL
    INSTALL_STATUS=$?
    if [ $INSTALL_STATUS -ne 0 ]; then
        echo "Error: Instalación/Verificación de Tomcat fallida en $host_ip (código de salida SSH: $INSTALL_STATUS)."
        continue
    fi

    # 4. Detener Tomcat (si está corriendo)
    echo "INFO: Intentando detener Tomcat (si está activo) en $host_ip..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$host_ip << EOF_STOP > /dev/null 2>&1
TOMCAT_SHUTDOWN_DIR="$REMOTE_TOMCAT_PATH"
PID=""
if [ -f "\$TOMCAT_SHUTDOWN_DIR/bin/shutdown.sh" ]; then
    echo "INFO_REMOTE: Ejecutando shutdown.sh..." >&2
    \$TOMCAT_SHUTDOWN_DIR/bin/shutdown.sh -force
    sleep 8

    PID=\$(pgrep -f "java.*-Dcatalina.home=.*\$TOMCAT_SHUTDOWN_DIR" || ps -ef | grep "java" | grep -E "Dcatalina.home=.*\$TOMCAT_SHUTDOWN_DIR" | grep -v grep | awk '{print \$2}' | head -n 1)
    if [ ! -z "\$PID" ]; then
        echo "INFO_REMOTE: Tomcat aún parece estar corriendo (PID: \$PID). Intentando kill -9..." >&2
        kill -9 \$PID
        sleep 2
    else
        echo "INFO_REMOTE: Tomcat parece haberse detenido correctamente o no estaba corriendo." >&2
    fi
else
    echo "INFO_REMOTE: shutdown.sh no encontrado. Asumiendo que Tomcat no está gestionado por este script." >&2
fi
exit 0
EOF_STOP

    # 5. Desplegar WAR en Tomcat
    echo "INFO: Desplegando $REMOTE_WAR_FILE en Tomcat en $host_ip..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$host_ip << EOF_DEPLOY > /dev/null 2>&1
TOMCAT_DEPLOY_DIR="$REMOTE_TOMCAT_PATH"
APP_SOURCE_PATH="$REMOTE_APP_PATH"
WAR_FILENAME="$(basename "$REMOTE_WAR_FILE")" # Usamos el nombre completo del WAR
APP_BASENAME="$APP_NAME" # Usamos el nombre sin extensión .war

mkdir -p \$TOMCAT_DEPLOY_DIR/webapps

echo "INFO_REMOTE: Limpiando despliegues anteriores de \$APP_BASENAME..." >&2
# Tomcat despliega el WAR en un directorio con el nombre base
rm -f \$TOMCAT_DEPLOY_DIR/webapps/\$WAR_FILENAME
rm -rf \$TOMCAT_DEPLOY_DIR/webapps/\$APP_BASENAME

echo "INFO_REMOTE: Copiando \$APP_SOURCE_PATH/\$WAR_FILENAME a \$TOMCAT_DEPLOY_DIR/webapps/..." >&2
cp "\$APP_SOURCE_PATH/\$WAR_FILENAME" "\$TOMCAT_DEPLOY_DIR/webapps/"

if [ ! -f "\$TOMCAT_DEPLOY_DIR/webapps/\$WAR_FILENAME" ]; then
    echo "ERROR_REMOTE: WAR no encontrado en webapps después de copiar: \$TOMCAT_DEPLOY_DIR/webapps/\$WAR_FILENAME" >&2
    exit 1
fi
echo "INFO_REMOTE: WAR copiado a webapps." >&2
exit 0
EOF_DEPLOY
    DEPLOY_STATUS=$?
    if [ $DEPLOY_STATUS -ne 0 ]; then
        echo "Error: Despliegue del WAR fallido en $host_ip (código de salida SSH: $DEPLOY_STATUS)."
        continue
    fi

    # 6. Iniciar Tomcat
    echo "INFO: Iniciando Tomcat en $host_ip..."
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null $REMOTE_USER@$host_ip << EOF_START > /dev/null 2>&1
TOMCAT_START_DIR="$REMOTE_TOMCAT_PATH"
export CATALINA_HOME="\$TOMCAT_START_DIR"
export CATALINA_BASE="\$TOMCAT_START_DIR"

chmod +x \$TOMCAT_START_DIR/bin/*.sh

echo "INFO_REMOTE: Ejecutando startup.sh..." >&2
nohup \$TOMCAT_START_DIR/bin/startup.sh > \$TOMCAT_START_DIR/logs/catalina.out 2>&1 &

sleep 3
if pgrep -f "java.*-Dcatalina.home=\$CATALINA_HOME" > /dev/null; then
    echo "INFO_REMOTE: Tomcat parece estar iniciándose." >&2
    exit 0
else
    echo "ERROR_REMOTE: Tomcat no parece haberse iniciado. Revisa \$CATALINA_HOME/logs/catalina.out" >&2
    exit 1
fi
EOF_START
    START_STATUS=$?
    # La URL esperada se basa en el nombre del WAR sin extensión (como lo despliega Tomcat)
    FINAL_URL="http://$host_ip:8080/$(basename "$REMOTE_WAR_FILE" .war)"
    if [ $START_STATUS -ne 0 ]; then
        echo "Error: Inicio de Tomcat fallido o no confirmado en $host_ip (código de salida SSH: $START_STATUS)."
    else
        echo "[+] Despliegue e inicio de Tomcat solicitado en $host_ip (URL esperada: $FINAL_URL)"
    fi
    echo "======================================================================================"
    echo

done # Fin del bucle for host_entry

echo "[!] Proceso multi-host de despliegue finalizado."

echo ""
echo "--- Ejecutando Gestor localmente ---"

# Directorio donde se encuentra el JAR del cliente
CLIENT_JAR="$LOCAL_CLIENT_JAR"

# Directorio temporal para extraer el proyecto
TEMP_DIR="proyecto_temp_local_gestor"

# Limpiar directorio temporal previo si existe
echo "Limpiando directorio temporal previo: $TEMP_DIR"
if [ -d "$TEMP_DIR" ]; then
    find "$TEMP_DIR" -delete 2>/dev/null || rm -rf "$TEMP_DIR"
fi

# Crear directorio temporal
echo "Creando directorio temporal: $TEMP_DIR"
if ! mkdir -p "$TEMP_DIR"; then
    echo "Error: Fallo al crear el directorio temporal '$TEMP_DIR'. Saliendo."
    exit 1
fi

# Extraer el JAR del cliente al directorio temporal
echo "Extrayendo el archivo JAR del cliente '$CLIENT_JAR' a '$TEMP_DIR'..."
if ! unzip -q "$CLIENT_JAR" -d "$TEMP_DIR"; then
    echo "Error: Fallo al extraer el archivo JAR del cliente '$CLIENT_JAR'. Asegúrate de que existe y es un archivo ZIP válido."
    rm -rf "$TEMP_DIR"
    exit 1
fi

echo "Extracción del JAR del cliente completada."

LIB_DIR="$TEMP_DIR/WebContent/WEB-INF/lib"
GESTOR_JAVA_FILE="$TEMP_DIR/cliente/Gestor.java"

if [ ! -d "$LIB_DIR" ]; then
    echo "Error: Directorio de librerías esperado no encontrado después de la extracción del JAR: '$LIB_DIR'."
    echo "       Asegúrate de que el JAR contiene la estructura 'WebContent/WEB-INF/lib'."
    rm -rf "$TEMP_DIR" # Limpiar antes de salir
    exit 1
fi
echo "Verificación: Directorio de librerías encontrado: '$LIB_DIR'."

# Verificamos si el archivo .java existe para compilar
COMPILE_GESTOR=false
if [ -f "$GESTOR_JAVA_FILE" ]; then
    COMPILE_GESTOR=true
else
    echo "Advertencia: Archivo Gestor.java no encontrado en la ruta esperada después de la extracción del JAR: '$GESTOR_JAVA_FILE'."
fi

# Crear una variable con todos los JARs de dependencias
CLASSPATH=""
echo "Construyendo CLASSPATH con JARs de '$LIB_DIR'..."
# Añadir el directorio actual del JAR extraído al CLASSPATH primero
CLASSPATH="$TEMP_DIR"
for jar in "$LIB_DIR"/*.jar; do
    if [ -f "$jar" ]; then # Verificar si es un archivo
        CLASSPATH="$CLASSPATH:$jar"
    fi
done
echo "CLASSPATH generado: $CLASSPATH"


# Compilar el archivo Gestor.java si se encontró el fuente
if [ "$COMPILE_GESTOR" = true ]; then
    echo "Compilando Gestor.java desde '$GESTOR_JAVA_FILE'..."
    if ! javac -cp "$CLASSPATH" -d "$TEMP_DIR" "$GESTOR_JAVA_FILE"; then
        echo "Error: Fallo al compilar Gestor.java. Revisa los errores de compilación"
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    echo "Compilación completada."
fi

# Ejecutar la clase principal
GESTOR_CLASS_PATH="$TEMP_DIR/cliente/Gestor.class"

if [ ! -f "$GESTOR_CLASS_PATH" ]; then
    echo "Error: Archivo Gestor.class no encontrado en '$GESTOR_CLASS_PATH'."
    if [ "$COMPILE_GESTOR" = true ]; then
        echo "       La compilación de Gestor.java pudo haber fallado o la clase no se generó donde se esperaba."
    else
        echo "       No se encontró 'cliente/Gestor.class' en el JAR extraído."
    fi
    rm -rf "$TEMP_DIR"
    exit 1
fi

echo "Ejecutando cliente.Gestor..."
if ! java -cp "$TEMP_DIR:$CLASSPATH" cliente.Gestor; then
    echo "Error: Fallo al ejecutar cliente.Gestor."
fi
echo "Ejecución de Gestor completada."


# Limpiar shareKeys.sh descargado
if [ -f "$SCRIPT_DIR/shareKeys.sh" ]; then
    echo "INFO: Eliminando shareKeys.sh descargado."
    rm -f "$SCRIPT_DIR/shareKeys.sh"
fi

echo ""
echo "[!] Proceso completo finalizado."

exit 0
