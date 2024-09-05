@file:Suppress("RedundantVisibilityModifier", "unused")
package kcommand.commandbuilder

import edu.wpi.first.wpilibj2.command.*
import kotlin.properties.ReadOnlyProperty
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
    @PublishedApi
    internal var commands: LinkedHashSet<Command> = linkedSetOf() // LinkedHashSet keeps commands in order, but also ensures they're not added multiple times

    @PublishedApi
    internal var commandModificationBlocked: Boolean = false

    private fun errorDuringRuntimeCommandModification(){
        require(!commandModificationBlocked){
            """
                It seems that you are attempting to add a command to the CommandBuilder during runtime. This is not allowed.
                Make sure that you don't have any nested runOnce, loop, loopUntil, etc. blocks 
                within another command-adding block, and no explicit builtCommand receivers are called.
                
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
        }
    }

    /**
     * Applies a generic modifier to a command.
     * This function must be used for command decorators(withTimeout, unless, raceWith)
     * in order for them to be actually applied.
     * For instance:
     *
     * ```
     * buildCommand{
     *      loop{ println("hi") }.modify{ it.withTimeout(5) }
     *
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
     * Usage Example:
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
    public operator fun <C : Command> C.unaryPlus(): C {
        errorDuringRuntimeCommandModification()
        commands.add(this)
        return this
    }

    /**
     * Returns a command, then removes it from the set of commands within the [CommandBuilder].
     *
     * ```
     * buildCommand{
     *    val command by getOnceDuringRun{
     *      -runOnce{ doSomethingHere()} // will no longer be added to the command builder
     *    }
     * }
     * ```
     */
    public operator fun <C: Command> C.unaryMinus(): C {
        errorDuringRuntimeCommandModification()
        commands.remove(this)
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
     * Adds a command that will run the command onTrue or only if a condition is met.
     *
     * Equivalent to a [ConditionalCommand].
     *
     * @param condition the condition supplier utilized.
     * @param onTrue the command ran when the condition returns true.
     * @param onFalse the command ran when the condition returns false; defaults to an empty command.
     */
    public fun runIf(condition: () -> Boolean, onTrue: Command, onFalse: Command = InstantCommand()): Command =
        ConditionalCommand(onTrue, onFalse, condition).also{
            +it
            this.commands.remove(onTrue)
            this.commands.remove(onFalse)
        }

    /**
     * Adds a command that will run the appropriate mapped command, depending on the key given.
     *
     * Equivalent to a [SelectCommand].
     *
     * @param key: A lambda that gets a generic value, used for choosing an appropriate command.
     * @param commands: A map between the key and commands to be called.
     */
    public fun <T: Any> runWhen(key: () -> T, commands: Map<T, Command>): Command =
        SelectCommand(commands, key).also{
            +it
            this.commands.removeAll(commands.values.toSet())
        }


    /**
     * Adds a command that will run *until* the [condition] is met.
     *
     * Equivalent to the [Command.until] decorator.
     *
     * @param condition the condition to be met
     * @param command the command to run until [condition] is met
     */
    public fun runUntil(condition: () -> Boolean, command: Command): Command =
        command.until(condition)
            .also{
                +it
                this.commands.remove(command)
            }

    /**
     * Adds a command that will run *while* the [condition] is met.
     *
     * Equivalent to the [Command.onlyWhile] decorator.
     *
     * @param condition the condition to be met
     * @param command the command to run until [condition] is met
     */
    public fun runWhile(condition: () -> Boolean, command: Command): Command =
        command.onlyWhile(condition)
            .also{
                +it
                this.commands.remove(command)
            }

    /**
     * Adds several commands that will run at the same time, all stopping as soon as one finishes.
     * These commands can either be specified as a function parameter, or as a builder block
     * within the command builder context [block].
     *
     * Equivalent to a [ParallelRaceGroup]
     *
     * @param commands commands to run in parallel;
     * these are automatically removed from the overarching builder(so you can use runOnce/loopUntil).
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun runParallelUntilOneFinishes(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command {
        val builder = CommandBuilder().apply(block)
        val commandsSet = commands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        builder.commandModificationBlocked = true
        return +ParallelRaceGroup(*commandsSet.toTypedArray())
    }

    /**
     * Adds several commands that will run at the same time, all stopping as soon as the first command specified finishes.
     * These commands can either be specified as a function parameter, or as a builder block
     * within the command builder context [block].
     *
     * Equivalent to a [ParallelDeadlineGroup].
     *
     * @param commands commands to run in parallel;
     * these are automatically removed from the overarching builder(so you can use runOnce/loopUntil).
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun runParallelUntilFirstCommandFinishes(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command {
        val builder = CommandBuilder().apply(block)
        val commandsSet = commands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        builder.commandModificationBlocked = true
        try{
            val firstCommand = commandsSet.first()
            val otherCommands = commandsSet - firstCommand
            return +ParallelDeadlineGroup(firstCommand, *otherCommands.toTypedArray())
        }catch(e: NoSuchElementException){
            return +InstantCommand()
        }
    }

    /**
     * Adds several commands that will run at the same time, only finishing once all are complete.
     * These commands can either be specified as a function parameter, or as a builder block
     * within the command builder context [block].
     *
     * Equivalent to a [ParallelCommandGroup].
     *
     * @param commands commands to run in parallel;
     * these are automatically removed from the overarching builder(so you can use runOnce/loopUntil).
     * @param block a builder allowing more parallel commands to be defined and added
     */
    public inline fun runParallelUntilAllFinish(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command {
        val builder = CommandBuilder().apply(block)
        val commandsSet = commands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        builder.commandModificationBlocked = true
        return +ParallelCommandGroup(*commandsSet.toTypedArray())
    }

    /**
     * Adds several commands that will run one after another.
     * These commands can either be specified as a function parameter, or as a builder block
     * within the command builder context [block].
     *
     * Equivalent to a [SequentialCommandGroup].
     *
     * @param commands explicitly specified commands to be run sequentially;
     * these are automatically removed from the overarching builder(so you can use runOnce/loopUntil).
     * @param block a builder to create the commands to run sequentially
     */
    public inline fun runSequentially(vararg commands: Command, block: CommandBuilder.() -> Unit = {}): Command {
        val builder = CommandBuilder().apply(block)
        val commandsSet = commands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        builder.commandModificationBlocked = true
        return +SequentialCommandGroup(*commandsSet.toTypedArray())
    }

    /**
     * Adds a command that will schedule the given commands provided
     * instead of running them sequentially within the command group itself.
     *
     * This is useful for forking off of command groups.
     *
     *
     * @param block a builder to create the commands to run sequentially
     * @see ScheduleCommand
     */
    public inline fun runSeparately(vararg commands: Command, block: CommandBuilder.() -> Unit): Command {
        val builder = CommandBuilder().apply(block)
        val commandsSet = commands.toMutableSet() + builder.commands
        this.commands.removeAll(commandsSet)
        builder.commandModificationBlocked = true
        return +ScheduleCommand(*commandsSet.toTypedArray())
    }

    /**
     * Adds a command that will run until either the time expires or it completes on its own.
     *
     * Equivalent to the [Command.withTimeout] decorator.
     *
     * @param command the command to run
     * @param seconds the maximum allowed runtime of the command
     */
    public fun runForDuration(seconds: Number, command: Command): Command =
        command.withTimeout(seconds.toDouble()).also{ +it }

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
     * Adds a command that does nothing for a specified time interval, then completes.
     *
     * Useful if a delay is needed between two commands in a [SequentialCommandGroup].
     */
    public fun wait(seconds: Number): Command =
        +WaitCommand(seconds.toDouble())

    /**
     * Adds a command that does nothing until a [condition] is met, then completes.
     *
     * Useful if some condition must be met before proceeding to the next command in a [SequentialCommandGroup].
     */
    public fun waitUntil(condition: () -> Boolean): Command =
        +WaitUntilCommand(condition)

    /**
     * Creates a value that will refresh once during run;
     * at the point of which this statement is placed within the command.
     *
     * In order to do this, read-only properties(val)
     * within the command builder block have their getValue function "delegated" by this function.
     *
     * See [here](https://kotlinlang.org/docs/delegated-properties.html#standard-delegates)
     * for an explanation of property delegates.
     *
     * ```
     * val command = buildCommand{
     *      // this fetches the value of armStartingPosition when the buildCommand is created
     *      // this means that p2 will not refresh itself when the command runs, unlike armStartingPosition.
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
    public fun <T : Any> getOnceDuringRun(get: CodeBlockContext.() -> T) : ReadOnlyProperty<Any?, T> =
        object: ReadWriteProperty<Any?, T> {
            private lateinit var value: T

            private var hasInitialized: Boolean = false

            private fun initializeValue() {
                value = CodeBlockContext.get()
                hasInitialized = true
            }

            init {
                // Add a new command that initializes this value in its initialize() function.
                +InstantCommand({ if (!hasInitialized) initializeValue() })
                    // sets hasInitialized to false when command ends,
                    // so that the value can re-initialize when the buildCommand is executed again.
                    .finallyDo{ _ -> hasInitialized = false }
            }

            override fun getValue(thisRef: Any?, property: KProperty<*>): T =
                try {
                    value
                } catch (e: UninitializedPropertyAccessException) { // If value is tried to be used before the initializer command runs (for example, by another command running in parallel), then initialize it immediately.
                    initializeValue()
                    value
                }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                this.value = value
            }
        }
}
