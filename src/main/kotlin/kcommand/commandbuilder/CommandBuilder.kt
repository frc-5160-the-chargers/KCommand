@file:Suppress("RedundantVisibilityModifier", "unused")
package kcommand.commandbuilder

import edu.wpi.first.wpilibj.RobotBase
import edu.wpi.first.wpilibj2.command.*
import kcommand.internal.MutableConditionalCommand
import kcommand.internal.reportError
import kotlin.collections.LinkedHashSet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * This object serves to restrict the scope of runOnce, loopForever, etc. blocks within the buildCommand.
 *
 * For instance, this is now prevented:
 * ```
 * buildCommand{
 *      loop{
 *          // does not compile due to CodeBlockContext
 *          loop{
 *              println("hi")
 *          }
 *      }
 * }
 * ```
 * Which would factually do nothing due to the command already being created when buildCommand is initialized.
 */
@CommandBuilderMarker
public object CodeBlockContext

/**
 * This "marker" serves to restrict the scope of the buildCommand DSL.
 *
 * See [here](https://kotlinlang.org/docs/type-safe-builders.html#scope-control-dslmarker)
 * for the purpose of this annotation class.
 */
@DslMarker
public annotation class CommandBuilderMarker

/**
 * The scope class responsible for governing the BuildCommand DSL.
 *
 * @see buildCommand
 */
@CommandBuilderMarker
public open class CommandBuilder {
    // PublishedApi makes internal(only visible within library code) accessible by inline methods
    // LinkedHashSet keeps commands in order, but also ensures they're not added multiple times
    @PublishedApi
    internal var commands: LinkedHashSet<Command> = linkedSetOf()

    // Used to prevent adding requirements and command modification during runtime
    // This is already prevented with the DSL scope control system; however, this acts as an extra safety net in case explicit receivers are used.
    @PublishedApi
    internal var lockMutation: Boolean = false

    // stores all getOnceDuringRun delegates so that they can have their value reset before the command runs.
    @PublishedApi
    internal val allDelegates: MutableList<GetOnceDuringRunDelegate<*>> = mutableListOf()

    private fun errorIfCommandsModifiedDuringRuntime(){
        if (lockMutation) {
            reportError(
                """
                WARNING: 
                It seems that you are attempting to add a command to the CommandBuilder during runtime. This is not allowed.
                Make sure that you don't have any explicit buildCommand receivers in your code.
                
                buildCommand{
                    runOnce{
                        // NOT ALLOWED
                        this@buildCommand.runOnce{
                            doSomething()
                        }
                        // Will not compile(DSL scope enforcement)
                        runOnce{
                            doSomethingElse()
                        }
                    }
                } 
                """.trimIndent()
            )
        }
    }

    @PublishedApi
    internal inline fun getCommandsArray(
        vararg otherCommands: Command,
        block: CommandBuilder.() -> Unit
    ): Array<Command> {
        val builder = CommandBuilder().apply(block)
        this.allDelegates.addAll(builder.allDelegates) // adds getOnceDuringRun delegates to the main builder
        builder.lockMutation = true
        val commandsSet = otherCommands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        return commandsSet.toTypedArray()
    }

    /**
     * Applies a generic modifier to a command.
     * This function must be used for command decorators(withTimeout, unless, raceWith)
     * in order for them to be applied to command blocks.
     * For instance:
     *
     * ```
     * buildCommand{
     *      loop{ println("hi") }.modify{ it.withTimeout(5) }
     * }
     */
    public fun Command.modify(modifier: (Command) -> Command): Command {
        commands.remove(this)
        val newCommand = modifier(this)
        commands.add(newCommand)
        return newCommand
    }

    /**
     * Adds a single command to be run until its completion.
     * ```
     * class ArmCommand(val angle: Angle, val arm: Arm): Command(){...}
     *
     * val command = buildCommand{
     *      // here, "+" is called like a function,
     *      // and makes the ArmCommand run until completion within the buildCommand.
     *      +ArmCommand(2.0.radians, armSubsystem)
     * }
     * ```
     */
    public operator fun <C : Command?> C.unaryPlus(): C {
        errorIfCommandsModifiedDuringRuntime()
        if (this != null) commands.add(this)
        return this
    }

    /**
     * Returns a command, then removes it from the set of commands within the [CommandBuilder] (if it exists).
     */
    public operator fun <C: Command?> C.unaryMinus(): C {
        errorIfCommandsModifiedDuringRuntime()
        if (this != null) commands.remove(this)
        return this
    }

    /**
     * Adds a command that will run once and then complete.
     *
     * Equivalent to an [InstantCommand].
     *
     * @param execute the code to be run
     */
    public fun runOnce(execute: CodeBlockContext.() -> Unit): Command =
        +InstantCommand({ CodeBlockContext.execute() })

    /**
     * Adds a command that will run the code block repeatedly *until* the [condition] is met.
     *
     * @param condition the condition to be met
     * @param execute the code to be run until [condition] is met
     */
    public fun loopUntil(condition: () -> Boolean, execute: CodeBlockContext.() -> Unit): Command =
        RunCommand({ CodeBlockContext.execute() }).until(condition).also{ +it }

    /**
     * Adds a command that will run *while* [condition] is true.
     *
     * @param condition the condition to be met
     * @param execute the code to be run
     */
    public fun loopWhile(condition: () -> Boolean, execute: CodeBlockContext.() -> Unit): Command =
        RunCommand({ CodeBlockContext.execute() }).onlyWhile(condition).also{ +it }

    /**
     * Adds a command that will run until either the time expires or it completes on its own.
     *
     * @param seconds the maximum allowed runtime of the command
     * @param execute the code to be run
     */
    public fun loopForDuration(seconds: Number, execute: CodeBlockContext.() -> Unit): Command =
        RunCommand({ CodeBlockContext.execute() }).withTimeout(seconds.toDouble()).also{ +it }

    /**
     * Adds a command to be run continuously.
     *
     * Equivalent to a [RunCommand].
     *
     * @param execute the code to be run
     */
    public fun loop(execute: CodeBlockContext.() -> Unit): Command =
        +RunCommand({ CodeBlockContext.execute() })

    /**
     * Adds several commands that will run at the same time, all stopping as soon as one finishes.
     *
     * The [block] below has the context of a [CommandBuilder], meaning that adding commands
     * via context methods or the + operator within will add them to the respective parallel command.
     *
     * Equivalent to a [ParallelRaceGroup].
     *
     * @param commands commands to run in parallel
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun parallelUntilOneEnds(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command{
        val allCommands = getCommandsArray(*commands, block=block)
        if (allCommands.any{ it is InstantCommand }){
            error("InstantCommands(or properties delegated by getOnceDuringRun) are not allowed in a parallelUntilOneFinishes block.")
        }
        return +ParallelRaceGroup(*allCommands)
    }

    /**
     * Adds several commands that will run at the same time, all stopping as soon as
     * the first command specified (the chief) finishes.
     * **Do not use getOnceDuringRun statements in this block.**
     *
     * The [block] below has the context of a [CommandBuilder], meaning that adding commands
     * via context methods or the + operator within will add them to the respective parallel command.
     *
     * Equivalent to a [ParallelDeadlineGroup].
     *
     * @param commands commands to run in parallel
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun parallelUntilChiefEnds(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command {
        val commandsArray = getCommandsArray(*commands, block=block)
        if (commandsArray.isNotEmpty()){
            val deadline = commandsArray[0]
            val otherCommands = (listOf(*commandsArray) - deadline).toTypedArray()
            // we want these to error on the real robot; thus we don't use reportError
            if (deadline is MutableConditionalCommand && !deadline.onFalseCommandWasSet()) {
                error("runSequenceIf statements without an orElse block are not allowed to be" +
                        "the first command/deadline of a parallelUntilLeadFinishes block." +
                        "Consider adding .orElse{ runOnce{} } at the end to make the deadline more clear.")
            }else if (deadline is InstantCommand) {
                error("The first command/deadline of a parallelUntilLeadFinishes block" +
                        "cannot be an InstantCommand or a getOnceDuringRun delegate.")
            }
            return +ParallelDeadlineGroup(deadline, *otherCommands)
        } else {
            return InstantCommand()
        }
    }

    /**
     * Adds several commands that will run at the same time, only finishing once all are complete.
     *
     * The [block] below has the context of a [CommandBuilder], meaning that adding commands
     * via context methods or the + operator within will add them to the respective parallel command.
     *
     * Equivalent to a [ParallelCommandGroup].
     *
     * @param commands commands to run in parallel;
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun parallelUntilAllEnd(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command =
        +ParallelCommandGroup(*getCommandsArray(*commands, block=block))

    /**
     * Adds several commands that will run one after another.
     * These commands can either be specified as a function parameter, or as a builder block
     * within the command builder context [block].
     *
     * Equivalent to a [SequentialCommandGroup].
     *
     * @param commands explicitly specified commands to be run sequentially
     * @param block a builder to create the commands to run sequentially
     */
    public inline fun runSequence(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command =
        +SequentialCommandGroup(*getCommandsArray(*commands, block=block))

    /**
     * Adds commands that run one after another,
     * stopping all of them after the duration expires.
     *
     * @see runSequence
     */
    public inline fun runSequenceForDuration(
        seconds: Number,
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): Command = runSequence(*commands, block=block).modify { it.withTimeout(seconds.toDouble()) }

    /**
     * Adds commands that run one after another *until* the [condition] turns true.
     *
     * @see runSequence
     */
    public inline fun runSequenceUntil(
        noinline condition: () -> Boolean,
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): Command = runSequence(*commands, block=block).modify{ it.until(condition) }

    /**
     * Adds commands that run one after another *while* the [condition] is true.
     *
     * @see runSequence
     */
    public inline fun runSequenceWhile(
        noinline condition: () -> Boolean,
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): Command = runSequence(*commands, block=block).modify { it.onlyWhile(condition) }

    /**
     * Runs a sequence if the [condition] returns true during runtime.
     *
     * ```
     * buildCommand {
     *      runSequenceIf({someCondition}) {
     *         ...
     *      }.orElseIf({someOtherCondition}) {
     *          loop{...}
     *      }.orElse {
     *          +command
     *      }
     * }
     * ```
     */
    public inline fun runSequenceIf(
        noinline condition: () -> Boolean,
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): MutableConditionalCommand {
        val sequentialCommand = runSequence(*commands, block=block)
        this.commands.remove(sequentialCommand)
        return +MutableConditionalCommand(mutableMapOf(condition to sequentialCommand))
    }

    /**
     * Adds an else-if condition to a [runSequenceIf] statement.
     *
     * The commands in [commands] and [block] are run sequentially.
     */
    public inline fun MutableConditionalCommand.orElseIf(
        noinline condition: () -> Boolean,
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): MutableConditionalCommand {
        this@orElseIf.addCommand(
            condition,
            SequentialCommandGroup(*getCommandsArray(*commands, block=block))
        )
        return this@orElseIf
    }

    /**
     * Adds an else condition to a [runSequenceIf] statement.
     *
     * The commands in [commands] and [block] are run sequentially.
     */
    public inline fun MutableConditionalCommand.orElse(
        vararg commands: Command,
        block: CommandBuilder.() -> Unit
    ): Command {
        this@orElse.setOnFalseCommand(
            SequentialCommandGroup(*getCommandsArray(*commands, block=block))
        )
        return this@orElse
    }

    /**
     * Adds a command that does nothing for a specified time interval, then completes.
     *
     * Useful if a delay is needed between two commands in a [SequentialCommandGroup].
     */
    public fun wait(seconds: Number): Command =
        +WaitCommand(seconds.toDouble())

    /**
     * Adds a command that waits forever.
     */
    public fun waitForever(): Command =
        +WaitCommand(Double.POSITIVE_INFINITY)

    /**
     * Adds a command that does nothing until a [condition] is met, then completes.
     *
     * Useful if some condition must be met before proceeding to the next command in a [SequentialCommandGroup].
     */
    public fun waitUntil(condition: () -> Boolean): Command =
        +WaitUntilCommand(condition)

    /**
     * Adds the commands within [block] only when the command runs on a real robot.
     */
    public inline fun realRobotOnly(block: () -> Unit) {
        if (RobotBase.isReal()) block()
    }

    /**
     * Adds the commands within [block] only when the command runs on a simulated robot.
     */
    public inline fun simOnly(block: () -> Unit) {
        if (RobotBase.isSimulation()) block()
    }

    /**
     * Creates a value that will refresh once during run;
     * at the point of which this statement is placed within the command.
     * Works for vars and vals.
     *
     * See [here](https://kotlinlang.org/docs/delegated-properties.html#standard-delegates)
     * for an explanation of property delegates.
     *
     * Unfortunately, getOnceDuringRun does not support nullable variables at this moment.
     * ```
     * val command = buildCommand{
     *      // this fetches the value of armStartingPosition when the buildCommand is created
     *      // this means that p2 will not refresh when the command runs.
     *      val p2 = arm.position
     *
     *      // this, on the other hand, is valid.
     *      val p2 by getOnceDuringRun{ arm.position }
     *      var p3 by getOnceDuringRun{ motor.appliedVoltage }
     *
     *      runOnce{
     *          // because this is called in a function block(CodeBlockContext),
     *          // it will print the new armStartingPosition whenever the command runs.
     *          println(armStartingPosition)
     *      }
     * }
     */
    public fun <T : Any> getOnceDuringRun(get: CodeBlockContext.() -> T) : ReadWriteProperty<Any?, T> =
        GetOnceDuringRunDelegate { CodeBlockContext.get() }

    public inner class GetOnceDuringRunDelegate<T: Any>(private val get: () -> T): ReadWriteProperty<Any?, T> {
        private var value: T? = null

        private fun initializeValue() { if (value == null) value = get() }

        public fun reset() { value = null }

        init {
            allDelegates.add(this)
            +InstantCommand(::initializeValue)
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            if (value == null) initializeValue()
            return value!!
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            this.value = value
        }
    }
}
