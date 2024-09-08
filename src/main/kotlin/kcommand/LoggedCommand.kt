package kcommand

import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.WrapperCommand

/**
 * Creates a command that automatically logs its execution time
 * and whether it is running or not.
 *
 * To log [kcommand.commandbuilder.buildCommand]s, pass in the log=true parameter
 * in the parameter list instead.
 */
public fun Command.withLogging(logName: String): LoggedCommand =
    LoggedCommand(this, logName)

/**
 * A command that automatically logs start/end times and whether the command is running or not.
 */
public class LoggedCommand(baseCommand: Command, private val logName: String): WrapperCommand(baseCommand) {
    public companion object {
        private var booleanLogger: (String, Boolean) -> Any = SmartDashboard::putBoolean
        private var doubleLogger: (String, Double) -> Any = SmartDashboard::putNumber
        private var category: String = "Commands"

        public fun configure(
            logCommandRunning: (String, Boolean) -> Unit,
            logCommandExecutionTime: (String, Double) -> Unit,
            category: String = "Commands"
        ) {
            this.booleanLogger = logCommandRunning
            this.doubleLogger = logCommandExecutionTime
            this.category = category
        }
    }

    private var startTime = Timer.getFPGATimestamp()

    init {
        booleanLogger("$category/$logName/IsRunning", false)
        doubleLogger("$category/$logName/ExecutionTimeSecs", 0.0)
    }

    override fun initialize(){
        startTime = Timer.getFPGATimestamp()
        super.initialize()
        booleanLogger("$category/$logName/IsRunning", true)
    }

    override fun end(interrupted: Boolean){
        super.end(interrupted)
        booleanLogger("$category/$logName/IsRunning", false)
        doubleLogger("$category/$logName/ExecutionTime", Timer.getFPGATimestamp() - startTime)
    }
}