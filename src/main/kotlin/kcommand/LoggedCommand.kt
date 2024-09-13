package kcommand

import edu.wpi.first.wpilibj.Timer
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard
import edu.wpi.first.wpilibj2.command.Command
import edu.wpi.first.wpilibj2.command.WrapperCommand

/**
 * Infix syntax to create a [LoggedCommand].
 */
public fun Command.withLog(logName: String = this.name): LoggedCommand =
    LoggedCommand(this, logName)

/**
 * A command that automatically logs execution time
 * and whether the command itself is active or not.
 *
 * To log [kcommand.commandbuilder.buildCommand]s, pass in the log=true parameter
 * in the parameter list instead.
 *
 * By default, this logs to smart dashboard; if you want to change this, call the
 * [configure] method within the robotInit() method.
 */
public class LoggedCommand(baseCommand: Command, private val logName: String): WrapperCommand(baseCommand) {
    public companion object {
        private var booleanLogger: (String, Boolean) -> Any = SmartDashboard::putBoolean
        private var doubleLogger: (String, Double) -> Any = SmartDashboard::putNumber
        private var category: String = "Commands"

        public fun configure(
            logCommandRunning: (String, Boolean) -> Unit,
            logExecutionTime: (String, Double) -> Unit,
            category: String = "Commands"
        ) {
            this.booleanLogger = logCommandRunning
            this.doubleLogger = logExecutionTime
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
        doubleLogger("$category/$logName/ExecutionTimeSecs", Timer.getFPGATimestamp() - startTime)
    }
}