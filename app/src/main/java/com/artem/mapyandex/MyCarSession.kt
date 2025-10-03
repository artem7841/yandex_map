// MyCarSession.kt
import android.content.Intent
import androidx.car.app.Session
import androidx.car.app.Screen
import com.artem.mapyandex.CamerasCarScreen
import com.artem.mapyandex.MainCarScreen

class MyCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }

}