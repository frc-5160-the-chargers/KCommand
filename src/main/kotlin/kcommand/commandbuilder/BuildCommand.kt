package kcommand.commandbuilder

import edu.wpi.first.wpilibj2.command.*
import kcommand.withLogging
import kotlin.experimental.ExperimentalTypeInference

/**
 * The entry point for the CommandBuilder DSL (Domain Specific Language).
 *
 *
 * See [here](https://kotlinlang.org/docs/type-safe-builders.html#how-it-works)
 * for an explanation of DSLs and how they are built.
 *
 * ```
 * val armCommand: Command = buildCommand{
 *      require(arm) // adds requirements across the entire command group
 *
 *      // equivalent to an InstantCommand within a SequentialCommandGroup
 *      runOnce{ // requirements of the entire command group can also be added here
 *          armSubsystem.resetEncoders()
 *      }
 *
 *      // variable initialization is allowed, since the block is essentially a lambda function
 *      val pidController = UnitSuperPIDController(...)
 *      // getOnceDuringRun acts as a value that is refreshed once every time the command is scheduled;
 *      // order is synchronous
 *      val armCurrentPosition by getOnceDuringRun{arm.distalAngle}
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
    name: String = "Unnamed BuildCommand",
    log: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    val builder = BuildCommandScope().apply(block)

    val subCommands = builder.commands.map{
        if (log) it.withLogging("$name/${it.name}") else it
    }.toTypedArray()

    if (builder.requirements.size > 0) {
        // adds the builder's requirements to the first command
        subCommands[0] = object: WrapperCommand(subCommands[0]) {
            init {
                addRequirements(*builder.requirements.toTypedArray())
            }
        }
    }

    builder.commandModificationBlocked = true
    builder.addingRequirementsLocked = true

    return SequentialCommandGroup(*subCommands)
        .withName(name)
        .until{ builder.stopped }
        .finallyDo(builder.endBehavior)
        .withLogging("$name/overallCommand")
}

/**
* Creates a [buildCommand] that automatically requires a subsystem.
*/
public inline fun Subsystem.buildCommand(
    name: String = "Generic BuildCommand of " + getName(),
    logIndividualCommands: Boolean = false,
    block: BuildCommandScope.() -> Unit
): Command {
    val subsystem = this
    return kcommand.commandbuilder.buildCommand(name, logIndividualCommands){
        require(subsystem)
        block()
    }
}


/**
 * A command request, to be returned within a [BuildCommandScope.loop]
 * or [BuildCommandScope.loopForDuration] block.
 */
public enum class Request {
    CONTINUE,
    BREAK,
    STOP_COMMAND,
}

/**
 * A scope exclusive to [buildCommand]; this contains things like end behavior and command requirements
 * which aren't used in other places where a [CommandBuilder] scope is asked for
 * (like runSequentially, runParallelUntilAllFinish, etc.)
 */
@CommandBuilderMarker
@OptIn(ExperimentalTypeInference::class)
public class BuildCommandScope: CommandBuilder() {
    public val requirements: LinkedHashSet<Subsystem> = linkedSetOf()

    public var addingRequirementsLocked: Boolean = false

    public var stopped: Boolean = false
        private set

    public var endBehavior: (Boolean) -> Unit = {}
        private set

    /**
     * Adds subsystems that are required across the entire [buildCommand].
     */
    public fun require(vararg requirements: Subsystem){
        if (addingRequirementsLocked){
            error("""
                It looks like you are attempting to add requirements to the buildCommand while it is running.
                This is not allowed.
                
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
     * Runs the function block when the [buildCommand] is finished.
     */
    public fun onEnd(run: (Boolean) -> Unit){
        endBehavior = run
    }

    /**
     * Stops the entire [buildCommand] if the condition is met;
     * otherwise, continues execution.
     * Code within an [onEnd] block will still be run.
     */
    public fun stopIf(condition: () -> Boolean): Command =
        +InstantCommand({
            if (condition()){
                this@BuildCommandScope.stopped = true
            }
        })

    /**
     * A variant of [CommandBuilder.loop] whose [run] block must return a [Request].
     *
     * This allows you to end the entire build command(by returning [Request.STOP_COMMAND])
     * during runtime, and end the block's execution by returning [Request.BREAK].
     * To continue like normal, use [Request.CONTINUE].
     *
     * This can only be called in the main [buildCommand] block, and not in parallel/sequential blocks.
     */
    @OverloadResolutionByLambdaReturnType
    @JvmName("LoopWithBreakAndReturn")
    public fun loop(run: CodeBlockContext.() -> Request): Command =
        object: Command() {
            init{ +this }

            private var result = Request.CONTINUE

            override fun execute() {
                result = CodeBlockContext.run()
                this@BuildCommandScope.stopped = result == Request.STOP_COMMAND
            }

            override fun isFinished(): Boolean = result != Request.CONTINUE
        }

    /**
     * A variant of [CommandBuilder.loopForDuration] whose [run] block must return a [Request].
     *
     * @see BuildCommandScope.loop
     */
    @OverloadResolutionByLambdaReturnType
    @JvmName("LoopForWithBreakAndReturn")
    public fun loopForDuration(seconds: Double, run: CodeBlockContext.() -> Request): Command =
        loop{ return@loop run() }.modify{ it.withTimeout(seconds) }
}