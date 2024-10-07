package kcommand.internal

import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.RobotBase

/**
 * Reports a kcommand error that is non-breaking.
 */
@PublishedApi
internal fun nonBreakingError(msg: String){
    if (RobotBase.isSimulation()){
        error(msg)
    } else {
        DriverStation.reportError(msg, true)
    }
}