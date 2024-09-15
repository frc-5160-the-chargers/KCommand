package kcommand.commandbuilder

import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj2.command.*
import kcommand.internal.reportError
import kcommand.withLog

/**
 * The entry point for the CommandBuilder DSL (Domain Specific Language).
 *
 * See [here](https://kotlinlang.org/docs/type-safe-builders.html#how-it-works)
 * for an explanation of DSLs and how they are built.
 *
 * Note: NEVER use an explicit buildCommand receiver(this@buildCommand) within this DSL.
 *
 * ```
 * val armCommand: Command = buildCommand{
 *      require(arm) // adds subsystem requirements
 *
 *      // equivalent to an InstantCommand within a SequentialCommandGroup
 *      runOnce{
 *          armSubsystem.resetEncoders()
 *      }
 *
 *      // variable initialization is allowed, since the block is essentially a lambda function
 *      val pidController = PIDController(...)
 *      // getOnceDuringRun acts as a value that is refreshed once every time the command is scheduled;
 *      // order is synchronous
 *      val armCurrentPosition by getOnceDuringRun{ arm.distalAngle }
 *
 *      // loops through the block below until the condition is met
 *      loopUntil(condition = { abs(pidController.target - arm.distalAngle) < 0.1 }){
 *          armSubsystem.setDistalVoltage(pidController.calculateOutput())
 *      }.modify{ it.unless(booleanSupplier).withTimeout(5) } // All command factory methods must be called from a modify() function call
 *
 * }
 * ```
 * @param name The name of the buildCommand(defaults to "Generic BuildCommand").
 * @param log If true, will log the individual commands that are part of the DSL. Defaults to false.
 * @param block The entry point to the DSL. Has the context of [CommandBuilder].
 */
public inline fun buildCommand(
    name: String? = null,
    log: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    if (name == null && log) {
        DriverStation.reportWarning("Warning: a buildCommand has the log = true property " +
            "but no name. All logged buildCommands should have a unique name.", true)
    }
    val builder = BuildCommandScope().apply(block)
    builder.lockMutation = true

    val subCommands = builder.commands
        .map { if (log) it.withLog("$name/${it.name}") else it }
        .toTypedArray()

    val sequentialCommand = SequentialCommandGroup(*subCommands)
        .until{ builder.entireCommandStopped }
        .finallyDo(builder.endBehavior)
        .withName(name ?: "Unnamed BuildCommand")
        .let{ if (log) it.withLog() else it }

    return object: WrapperCommand(sequentialCommand) {
        val allRequirements = (builder.requirements + sequentialCommand.requirements).toMutableSet()

        override fun initialize() {
            builder.allDelegates.forEach{ it.reset() }
            super.initialize()
        }

        override fun getRequirements(): MutableSet<Subsystem> = allRequirements
    }
}

/**
* Creates a [buildCommand] that automatically requires a subsystem.
*/
public inline fun Subsystem.buildCommand(
    name: String = "Unnamed BuildCommand of " + this.name,
    log: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    val subsystem = this
    return kcommand.commandbuilder.buildCommand(name, log){
        require(subsystem)
        block()
    }
}

/**
 * A scope exclusive to [buildCommand]; this contains things like end behavior and command requirements
 * which aren't used in other places where a [CommandBuilder] scope is asked for
 * (like runSequentially, runParallelUntilAllFinish, etc.)
 */
@CommandBuilderMarker
public class BuildCommandScope: CommandBuilder() {
    // PublishedApi internal makes an internal value(only visible within this project)
    // accessible via inline functions
    @PublishedApi
    internal val requirements: LinkedHashSet<Subsystem> = linkedSetOf()

    @PublishedApi
    internal var entireCommandStopped: Boolean = false

    @PublishedApi
    internal var endBehavior: (Boolean) -> Unit = {}

    /**
     * Adds subsystems that are required across the entire [buildCommand].
     */
    public fun require(vararg requirements: Subsystem){
        if (lockMutation){
            reportError("""
                WARNING: 
                It looks like you are attempting to add requirements to the buildCommand while it is running.
                This only happens if you are using an explicit buildCommand receiver(I.E this@buildCommand),
                which should never be used.
                buildCommand{
                    // correct way to add requirements; outside of any block
                    require(...)
                    
                    runOnce{
                        // does not compile
                        require(...)
                        // compiles, but will NOT WORK!
                        this@buildCommand.addRequirements(...)
                    }
                }
            """.trimIndent())
        }else{
            this.requirements.addAll(requirements)
        }
    }

    /**
     * Runs the function block when the [buildCommand] is finished,
     * regardless of it being interrupted or not.
     *
     * The boolean parameter is whether the command was interrupted or not.
     */
    public fun onEnd(run: (Boolean) -> Unit){
        endBehavior = run
    }

    /**
     * Stops the entire [buildCommand] if the condition is met;
     * otherwise, continues execution.
     *
     * Code within an [onEnd] block will still be run.
     */
    public fun stopIf(condition: () -> Boolean): Command =
        +InstantCommand({
            if (condition()){
                this@BuildCommandScope.entireCommandStopped = true
            }
        })
}