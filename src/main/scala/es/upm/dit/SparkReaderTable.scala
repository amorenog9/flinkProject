package es.upm.dit

import com.typesafe.config.ConfigFactory
import es.upm.dit.struct.{TrainEvent, TrainEventMemory}
import org.apache.commons.io.FileUtils
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.formats.json.JsonNodeDeserializationSchema
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, FlinkKafkaProducer}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{SparkSession, functions}
import org.apache.spark.sql.functions.{arrays_zip, col, explode, expr, flatten, size}

import java.io.File
import java.util.Properties
import util.Try
import sys.process._
import org.apache.flink.streaming.api.scala._
import io.circe.generic.auto._
import io.circe.syntax._

import java.lang.Thread.sleep



object SparkReaderTable{


  def main(args: Array[String]) {

    // Quitamos los mensajes de Info
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    // Config PARAMETERS
    val parametros = ConfigFactory.load("applicationTrain.conf")

    val pathTable = parametros.getString("TRAIN_DIR_TABLE")

    val id = parametros.getString("idEvento").toLowerCase()
    val event_type = parametros.getString("nombreEvento").toLowerCase()
    val date_event = parametros.getString("fechaEvento").toLowerCase()
    val lat = parametros.getString("latitudEvento").toLowerCase()
    val lng= parametros.getString("longitudEvento").toLowerCase()
    val location= parametros.getString("localizacionEvento").toLowerCase()

    val savePathEventsFromTimestamp = parametros.getString("PATH_EVENTS_FROM_TIMESTAMP")
    val pythonScriptFile = parametros.getString("PYTHON_KAFKA_PRODUCER_TIMESTAMP")

    val kafkaDir = parametros.getString("KAFKA_DIR")
    val kafkaMessagesIn = parametros.getString("KAFKA_TOPIC_TIMESTAMP_IN")
    val kafkaMessagesOut = parametros.getString("KAFKA_TOPIC_TIMESTAMP_OUT")


    // ---------------------------------------------------------------------------------------------------------------------------------
    // Llamada a funcion para este parametro query || Ojo que en flink si no es desde el principio deberiamos cambiar el CREADO EVENTO?
    // ---------------------------------------------------------------------------------------------------------------------------------

    //val timeStampValue = new TimeProcessing().getTimestampFromDate()
    val timeStampValue = 1L // Llamada a funcion


    val spark = SparkSession
      .builder
      .appName("DeltaReader")
      .master("local[*]")
      //.master("spark://alex-GE75-Raider-8SF:7077")
      .getOrCreate()

    //DF que tengo de la delta table hasta el momento de la ejecuci??n
    val df_load = spark.read
      .format("delta")
      //.option("versionAsOf", 5)
      .load(pathTable)

    // Debemos meter el current time millis aqui; Es decir una vez se al 100% que he cargado la tabla en memoria y esta tabla va a ser inmutable con respecto a las transformaciones que haga
    val actualTime = System.currentTimeMillis()
    println(actualTime)

    df_load.show()

    // Eliminamos los valores actuales de la tabla. No se necesitan para nada, estan en la memoria y trabajaremos con la memoria
    val df = df_load
      .drop("event_type")
      .drop("date_event")
      .drop("coordinates")
      .drop("location")


    // Este DF proporciona la ultima actualizaci??n de evento hasta la fecha seleccionada (incluyendo la fecha seleccionada)
    val actualDf = df.select(col("*"), expr(s"filter(date_event_memory, x -> x <= ${timeStampValue})").as("dates_before_timestamp"))
      .withColumn("index_size_before_date", size(col("dates_before_timestamp"))) //el array index empieza en 1 en spark array. Esta columna nos da el indice que empezar a leer event_type_memory
      .filter(size(col("dates_before_timestamp")) > 0)//eliminamos aquellas filas que no tengan eventos hasta la fecha seleccionada
      .withColumn("date_before_timestamp", functions.expr("slice (date_event_memory , index_size_before_date, 1)")) //tomamos unicamente 1 elemento empezando por el valor index_size_before_date (es decir, el ultimo hasta la fecha timeStampValue)
      .withColumn("event_before_timestamp", functions.expr("slice (event_type_memory , index_size_before_date, 1)"))
      .withColumn("coordinate_before_timestamp", functions.expr("slice (coordinates_memory , index_size_before_date, 1)"))
      .withColumn("location_before_timestamp", functions.expr("slice (location_memory , index_size_before_date, 1)"))
      .drop("date_event_memory")
      .drop("event_type_memory")
      .drop("coordinates_memory")
      .drop("location_memory")
      .drop("dates_before_timestamp")
      .drop("index_size_before_date")
      .withColumn(s"${date_event}", functions.expr("explode(date_before_timestamp)")) // transformo [ELEMENTO] a Elemento (quito el array)
      .withColumn(s"${event_type}", functions.expr("explode(event_before_timestamp)")) // transformo [ELEMENTO] a Elemento (quito el array)
      .withColumn(s"${location}", functions.expr("explode(location_before_timestamp)")) // transformo [ELEMENTO] a Elemento (quito el array)
      .withColumn("coordinate", functions.expr("flatten(coordinate_before_timestamp)")) // quitar con flatten 1 dimension del array (para que sea igual que como se reciben los eventos)
      .drop("date_before_timestamp")
      .drop("event_before_timestamp")
      .drop("coordinate_before_timestamp")
      .drop("location_before_timestamp")
      .withColumn(s"${lat}", col("coordinate").getItem(0)) // [lat, lng] lo transformo a lat
      .withColumn(s"${lng}", col("coordinate").getItem(1)) // [lat, lng] lo transformo a lng
      .drop("coordinate")

    actualDf.show()


    // Este DF filtra desde la fecha seleccionada (sin incluirse), los eventos que tiene en memoria la tabla hasta el momento en el que se ha stremeado a la BBDD
    val df2 = df.select(col("*"), expr(s"filter(date_event_memory, x -> x > ${timeStampValue})").as("dates_from_timestamp"))
      .withColumn("index_size_from_date", size(col("date_event_memory"))-size(col("dates_from_timestamp"))+1) //el array index empieza en 1 en spark array. Esta columna nos da el indice que empezar a leer event_type_memory
      .withColumn("events_from_timestamp", functions.expr("slice (event_type_memory , index_size_from_date, 999999999)")) //999999999 the length of the slice (valor maximo que podemos poner) -- suponemos infinito
      .withColumn("coordinates_from_timestamp", functions.expr("slice (coordinates_memory , index_size_from_date, 999999999)")) //coordenadas
      .withColumn("locations_from_timestamp", functions.expr("slice (location_memory , index_size_from_date, 999999999)")) //localizaciones
      .filter(size(col("dates_from_timestamp")) > 0)//eliminamos aquellas filas que no tengan eventos desde la fecha seleccionada
      .drop("index_size_from_date")
      .drop("date_event_memory") //borramos la columna que almacenaba las fechas (ahora solo queremos los eventos a partir de x fecha)
      .drop("event_type_memory") //borramos la columna que almacenaba los eventos (ahora solo queremos los eventos a partir de x fecha)
      .drop("coordinates_memory") //borramos la columna que almacenaba las posiciones (ahora solo queremos los eventos a partir de x fecha)
      .drop("location_memory")

    //df2.show()


    // Este DF desglosa los arrays (memory) en diferentes columnas y despues ordena por fecha del evento para poder streamearlo de arriba hacia abajo (como el fichero .feather)
    val df3 = df2.withColumn("zip_values", functions.expr("arrays_zip( dates_from_timestamp, events_from_timestamp, coordinates_from_timestamp, locations_from_timestamp) "))
      .withColumn("zip_values", functions.expr("explode(zip_values)"))
      .drop("dates_from_timestamp")
      .drop("events_from_timestamp")
      .drop("coordinates_from_timestamp")
      .drop("locations_from_timestamp")
      .select(col("*"), col("zip_values.dates_from_timestamp"), col("zip_values.events_from_timestamp"), col("zip_values.coordinates_from_timestamp"), col("zip_values.locations_from_timestamp"))
      .drop("zip_values")
      .withColumn(s"${lat}", col("coordinates_from_timestamp").getItem(0))
      .withColumn(s"${lng}", col("coordinates_from_timestamp").getItem(1))
      .drop("coordinates_from_timestamp")
      .withColumnRenamed("dates_from_timestamp", s"${date_event}")
      .withColumnRenamed("events_from_timestamp", s"${event_type}")
      .withColumnRenamed("locations_from_timestamp", s"${location}")
      .withColumnRenamed("id", s"${id}") //renmombramos la columna id que esta en minusculas (por venir del valor que se asigna en la case class de flink y lo ponemos en mayusculas, que es como esta en anubis.feather)
      .orderBy(s"${date_event}") //antes de enviar la tabla a JSON -> la ordenamos para que se haga el stream correctamente


    // opcion de almacenar los eventos posteriores ordenados para enviarlos a un archivo
    df3.show()

    // Si existe el directorio - lo borramos
    val dir = new File(savePathEventsFromTimestamp + s"/${timeStampValue}")
    if (dir.exists()) FileUtils.deleteDirectory(dir) // Cuidado con este

    //archivo json donde en cada linea hay un {} que sera cada fila del df
    df3.coalesce(1).write.format("json").save(savePathEventsFromTimestamp + s"/${timeStampValue}")
    println(s"Se ha almacenado el fichero con eventos posteriores a ${timeStampValue} en la carpeta: ${savePathEventsFromTimestamp + s"/${timeStampValue}"}")

    // eliminamos archivo /_SUCESS para que el producer unicamente lea el archivo JSON
    new File(savePathEventsFromTimestamp + s"/${timeStampValue}" + "/_SUCCESS").delete()
    println(s"Se ha borrado el archivo: savePathEventsFromTimestamp" + s"/${timeStampValue}" + "/_SUCCESS" + "para que este unicamente el JSON en la carpeta")

    //Cabiamos el nombre del JSON obtenido a uno mas sencillo de manejar
    def getListOfFiles(dir: String): List[File] = {
      val d = new File(dir)
      if (d.exists && d.isDirectory) {
        d.listFiles.filter(_.isFile).toList
      } else {
        List[File]()
      }
    }
    // Definimos funcion de renombrado
    def mv(oldName: String, newName: String) =
      Try(new File(oldName).renameTo(new File(newName))).getOrElse(false)

    // Buscamos por el unico archivo .json que hay en la carpeta para luego cambiarle el nombre
    val name : String = ".+\\.json"
    val files = getListOfFiles(savePathEventsFromTimestamp + s"/${timeStampValue}")
      .map(f => f.getName)
      .filter(_.matches(name))

    // Renombramos el archivo
    mv(savePathEventsFromTimestamp + s"/${timeStampValue}" + s"/${files.head}", savePathEventsFromTimestamp + s"/${timeStampValue}" + "/eventsFromTimestamp.json")


    // Borramos la tabla que nos proporciona las variables de entorno para python. Cada nueva llamada al script deber??a darnos una nueva tabla
    val pythonVariablesDeltaRoute = savePathEventsFromTimestamp + "/delta-table"
    val dir2 = new File(pythonVariablesDeltaRoute)
    if (dir2.exists()) FileUtils.deleteDirectory(dir2) // Cuidado con este

    // Creamos una delta table para enviar a python los parametros timestamp y localizacion del fichero
    val sc = spark.sparkContext
    val routeToFile = savePathEventsFromTimestamp + s"/${timeStampValue}"
    val rdd = sc.parallelize(
      Seq(
        (actualTime, routeToFile)
      )
    )
    val data = spark.createDataFrame(rdd).toDF("actualTime", "routeToFile")
    data.write.format("delta").save(pythonVariablesDeltaRoute)
    data.show()

    // -------------------------------------------------------------------------------------------
    // script para limpiar el kafka topic temporal (tanto de entrada como se salida)
    // Es necesario definir el directorio de Kafka (el docker esta conectado a los puertos del PC)
    // -------------------------------------------------------------------------------------------

    s"${kafkaDir}/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic ${kafkaMessagesIn}".!
    s"${kafkaDir}/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic ${kafkaMessagesOut}".!

    s"${kafkaDir}/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --replication-factor 1 --partitions 1 --topic ${kafkaMessagesIn}".!
    s"${kafkaDir}/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --replication-factor 1 --partitions 1 --topic ${kafkaMessagesOut}".!



    //Ejecutamos el script que realiza el stream de los eventos posteriores al timestamp almacenados en la BBDD
    s"python3 ${pythonScriptFile}".run() //con ! bloqueamos hasta que termine de enviarse lo del script; con run se paraleliza https://www.scala-lang.org/files/archive/api/current/scala/sys/process/ProcessBuilder.html


    // -------------------------------------------------------------------------------------
    // Flink procesado de eventos desde el topic mesages_from_timestamp_in
    //--------------------------------------------------------------------------------------

    println(s"Comienza flink-streaming en ${parametros.getString("NOMBRE_SISTEMA_TIMESTAMP")}")

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val kafkaProperties = new Properties()
    kafkaProperties.setProperty("bootstrap.servers", parametros.getString("KAFKA_DIRECTION_IN"))
    kafkaProperties.setProperty("group.id", "test")

    val kafkaConsumer2 = new FlinkKafkaConsumer(
      kafkaMessagesIn,
      new JsonNodeDeserializationSchema(), //deserializes a JSON String into an ObjectNode.
      kafkaProperties).setStartFromEarliest()

    val trainEvent: DataStream[TrainEvent] = env
      .addSource(kafkaConsumer2) //.setStartFromLatest())
      .map(jsonNode => TrainEvent(
        id = jsonNode.get(s"${id}").asText(),
        event_type = jsonNode.get(s"${event_type}").asText(),
        date_event = jsonNode.get(s"${date_event}").asLong(), // fecha en epoch milliseconds
        lat = jsonNode.get(s"${lat}").asDouble(),
        lng = jsonNode.get(s"${lng}").asDouble(),
        location = jsonNode.get(s"${location}").asText()
      ))

    val keyedEvents= trainEvent.keyBy(_.id)
      .flatMap(new EventProcessorFromTimestamp()) // de TRAINEVENT A TRAINEVENT, no veo necesario guardar en memoria, solo su visualizacion

    // Sinks
    keyedEvents.print()


    keyedEvents
      .map(_.asJson.noSpaces)
      .addSink(new FlinkKafkaProducer[String](
        parametros.getString("KAFKA_DIRECTION_OUT"),
        kafkaMessagesOut,
        new SimpleStringSchema))


    env.execute("Flink-Execution")



  }


}
