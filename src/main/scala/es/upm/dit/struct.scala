package es.upm.dit


// Si se cambian los parametros del tren en application.Conf => será necesario cambiar la estructura
object struct {
  case class UserEventPrompt(event_type_user: String, date_event_user: String, id_user: String, lat_user: String , lng_user: String , location_user: String ,correctParams: Boolean)

  case class TrainEvent(id: String, event_type: String, date_event: Long,  lat: Double, lng: Double, location: String) //Es como me viene el evento train del topic

  case class TrainEventMemory(id: String, event_type: String, date_event: Long,  coordinates: (Double, Double), location: String,
                              date_event_memory: List[Long], event_type_memory: List[String], coordinates_memory: List[(Double, Double)], location_memory: List[String]) //



}
