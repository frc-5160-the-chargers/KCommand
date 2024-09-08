package kcommand.internal

import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.RobotBase

@PublishedApi
internal fun reportError(msg: String){
    if (RobotBase.isSimulation()){
        error(msg)
    } else {
        DriverStation.reportError(msg, true)
    }
}