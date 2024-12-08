import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

data class CelestialDataResponse(
    val properties: Properties
)

data class Properties(
    val data: List<CelestialObject>
)

data class CelestialObject(
    val almanac_data: AlmanacData,
    val object: String
)

data class AlmanacData(
    val hc: Double, // Altitude
    val zn: Double  // Azimuth
)

interface CelestialApiService {
    @GET("api/celnav")
    fun getCelestialData(
        @Query("date") date: String,
        @Query("time") time: String,
        @Query("coords") coords: String
    ): Call<CelestialDataResponse>
}