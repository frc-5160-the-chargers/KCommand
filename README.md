# KCommand

KCommand is a revolutionary command library for kotlin FRC teams that expands off of WPILib's Command-based programming paradigm. With KCommand, teams can use an expressive
domain-specific language to write commands that combine the benefits of WPILib's subclassed commands and command groups.

## What does KCommand offer over the base java WPILib commands library?
### BuildCommand DSL:
   The buildCommand DSL essentially puts the JSON paradigm onto creating commands. The buildCommand DSL's syntax is designed to be easily readable while minimizing
   clutter and boilerplate code.

   Here is a side-by-side comparison between a buildCommand and a regular command composition:
   ``` kotlin
   val commandGroup = ConditionalCommand(
        Commands.sequence(
            Commands.run({
                shooter.run()
                groundIntake.run()
            }, shooter, groundIntake).until{ noteObserver.state = State.NoteInShooter },
            Commands.run({
                shooter.run()
                groundIntake.run()
            }).withTimeout(0.4)
        ),
        Commands.none(),
        RobotBase::isReal
   ).finallyDo{ _ ->
        groundIntake.setIdle()
        shooter.setIdle()
   }.withName("CommandGroup")
   
   val buildCommand = buildCommand("BuildCommandName"){
        require(shooter, groundIntake)
        realRobotOnly {
            loopUntil({noteObserver.state = State.NoteInShooter}){
                shooter.run()
                groundIntake.run()
            }
            loopForDuration(0.4){
                shooter.run()
                groundIntake.run()
            }
        }
        onEnd {
            shooter.setIdle()
            groundIntake.setIdle()
        }
   }
   ```
   The buildCommand DSL also supports comprehensive logging with the ```log = true``` parameter.
### Various command extensions:

    Even if you do plan to stick with the regular WPILib
    commands library, KCommand offers more kotlin-friendly versions of 
    various inline commands.
    
    As of now, this consists of overloads of InstantCommand and RunCommand 
    that uses external lambda syntax. For instance: 
     
    ```
    val runCommand = RunCommand(drivetrain) {
        drivetrain.drive()
    }
    // instead of
    val oldRunCommand = RunCommand({
        drivetrain.drive()
    }, drivetrain)
    ```
## How do I install/use KCommand?

   Our wiki has resources on installation, usage, and more!