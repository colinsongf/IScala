package org.refptr.iscala

import org.zeromq.ZMQ

import org.refptr.iscala.msg._
import org.refptr.iscala.msg.formats._

import Util.{log,debug}

trait Parent {
    val profile: Profile
    val ipy: Communication
    val interpreter: Interpreter
    var executeMsg: Option[Msg[Request]]
}

abstract class Handler[T <: Request](parent: Parent) extends ((ZMQ.Socket, Msg[T]) => Unit)

class ExecuteHandler(parent: Parent) extends Handler[execute_request](parent) {
    import parent.{ipy,interpreter}

    private def finish_stream(msg: Msg[_], std: Std) {
        std.output.flush()
        val count = std.input.available
        if (count > 0) {
            val buffer = new Array[Byte](count)
            std.input.read(buffer)
            ipy.send_stream(msg, std.name, new String(buffer))
        }
    }

    private def finish_streams(msg: Msg[_]) {
        finish_stream(msg, StdOut)
        finish_stream(msg, StdErr)
    }

    private def capture[T](block: => T): T = {
        Console.withOut(StdOut.stream) {
            Console.withErr(StdErr.stream) {
                block
            }
        }
    }

    private def pyerr_content(exception: Throwable, execution_count: Int): pyerr = {
        val ename = interpreter.stringify(exception.getClass.getName)
        val evalue = interpreter.stringify(exception.getMessage)
        val stacktrace = exception
             .getStackTrace()
             .takeWhile(_.getFileName != "<console>")
             .map(interpreter.stringify)
             .toList
        val traceback = s"$ename: $evalue" :: stacktrace.map("    " + _)

        pyerr(execution_count=execution_count,
              ename=ename,
              evalue=evalue,
              traceback=traceback)
    }

    def apply(socket: ZMQ.Socket, msg: Msg[execute_request]) {
        import interpreter.{n,In,Out}

        parent.executeMsg = Some(msg)

        val content = msg.content
        val code = content.code.replaceAll("\\s+$", "")
        val silent = content.silent || code.endsWith(";")
        val store_history = content.store_history getOrElse !silent

        if (code.trim.isEmpty) {
            ipy.send_ok(msg, interpreter.n)
            return
        }

        if (!silent) {
            interpreter++

            if (store_history) {
                In(n) = code
                interpreter.session.addHistory(n, code)
            }

            ipy.publish(msg.pub(MsgType.pyin,
                pyin(
                    execution_count=n,
                    code=code)))
        }

        ipy.send_status(ExecutionState.busy)

        try {
            code match {
                case Magic(name, input, Some(magic)) =>
                    capture { magic(interpreter, input) } match {
                        case None =>
                            finish_streams(msg)
                            ipy.send_ok(msg, n)
                        case Some(error) =>
                            finish_streams(msg)
                            ipy.send_error(msg, n, error)
                    }
                case Magic(name, _, None) =>
                    finish_streams(msg)
                    ipy.send_error(msg, n, s"ERROR: Line magic function `%$name` not found.")
                case _ =>
                    capture { interpreter.interpret(code) } match {
                        case Results.Success(value) =>
                            val result = if (silent) None else value.map(interpreter.stringify)

                            if (!silent && store_history) {
                                value.foreach(Out(n) = _)
                                result.foreach(interpreter.session.addOutputHistory(n, _))

                                interpreter.intp.beSilentDuring {
                                    value.foreach(interpreter.intp.bind("_" + interpreter.n, "Any", _))
                                }
                            }

                            finish_streams(msg)

                            result.foreach { data =>
                                ipy.publish(msg.pub(MsgType.pyout,
                                    pyout(
                                        execution_count=n,
                                        data=Data("text/plain" -> data))))
                            }

                            ipy.send_ok(msg, n)
                        case Results.Failure(exception) =>
                            finish_streams(msg)
                            ipy.send_error(msg, pyerr_content(exception, n))
                        case Results.Error =>
                            finish_streams(msg)
                            ipy.send_error(msg, n, interpreter.output.toString)
                        case Results.Incomplete =>
                            finish_streams(msg)
                            ipy.send_error(msg, n, "incomplete")
                        case Results.Cancelled =>
                            finish_streams(msg)
                            ipy.send_error(msg, n, "cancelled")
                    }
            }
        } catch {
            case exception: Throwable =>
                finish_streams(msg)
                ipy.send_error(msg, pyerr_content(exception, n)) // Internal Error
        } finally {
            interpreter.resetOutput()
            ipy.send_status(ExecutionState.idle)
        }
    }
}

class CompleteHandler(parent: Parent) extends Handler[complete_request](parent) {
    import parent.{ipy,interpreter}

    def apply(socket: ZMQ.Socket, msg: Msg[complete_request]) {
        val text = msg.content.text

        val matches = if (msg.content.line.startsWith("%")) {
            val prefix = text.stripPrefix("%")
            Magic.magics.map(_.name.name).filter(_.startsWith(prefix)).map("%" + _)
        } else {
            val completions = interpreter.completion.completions(text)
            val common = Util.commonPrefix(completions)
            var prefix = Util.suffixPrefix(text, common)
            completions.map(_.stripPrefix(prefix)).map(text + _)
        }

        ipy.send(socket, msg.reply(MsgType.complete_reply,
            complete_reply(
                status=ExecutionStatus.ok,
                matches=matches,
                matched_text=text)))
    }
}

class KernelInfoHandler(parent: Parent) extends Handler[kernel_info_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[kernel_info_request]) {
        val scalaVersion = Util.scalaVersion
            .split(Array('.', '-'))
            .take(3)
            .map(_.toInt)
            .toList

        ipy.send(socket, msg.reply(MsgType.kernel_info_reply,
            kernel_info_reply(
                protocol_version=(4, 0),
                language_version=scalaVersion,
                language="scala")))
    }
}

class ConnectHandler(parent: Parent) extends Handler[connect_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[connect_request]) {
        ipy.send(socket, msg.reply(MsgType.connect_reply,
            connect_reply(
                shell_port=parent.profile.shell_port,
                iopub_port=parent.profile.iopub_port,
                stdin_port=parent.profile.stdin_port,
                hb_port=parent.profile.hb_port)))
    }
}

class ShutdownHandler(parent: Parent) extends Handler[shutdown_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[shutdown_request]) {
        ipy.send(socket, msg.reply(MsgType.shutdown_reply,
            shutdown_reply(
                restart=msg.content.restart)))
        sys.exit()
    }
}

class ObjectInfoHandler(parent: Parent) extends Handler[object_info_request](parent) {
    import parent.ipy

    def apply(socket: ZMQ.Socket, msg: Msg[object_info_request]) {
        ipy.send(socket, msg.reply(MsgType.object_info_reply,
            object_info_notfound_reply(
                name=msg.content.oname)))
    }
}

class HistoryHandler(parent: Parent) extends Handler[history_request](parent) {
    import parent.{ipy,interpreter}

    def apply(socket: ZMQ.Socket, msg: Msg[history_request]) {
        import org.refptr.iscala.db.{DB,History,OutputHistory}

        import scala.slick.driver.SQLiteDriver.simple._
        import Database.threadLocalSession

        val raw = msg.content.raw

        var query = for {
            (input, output) <- History leftJoin OutputHistory on ((in, out) => in.session === out.session && in.line === out.line)
        } yield input.session ~ input.line ~ (if (raw) input.source_raw else input.source) ~ output.output.?

        msg.content.hist_access_type match {
            case HistAccessType.range =>
                val session = msg.content.session getOrElse 0

                val actualSession =
                    if (session == 0) interpreter.session.id
                    else if (session > 0) session
                    else interpreter.session.id - session

                query = query.filter(_._1 === actualSession)

                for (start <- msg.content.start)
                    query = query.filter(_._2 >= start)

                for (stop <- msg.content.stop)
                    query = query.filter(_._2 < stop)
            case HistAccessType.tail | HistAccessType.search =>
                // TODO: add support for `pattern` and `unique`
                query = query.sortBy(r => (r._1.desc, r._2.desc))

                for (n <- msg.content.n)
                    query = query.take(n)
        }

        val rawHistory = DB.db.withSession { query.list }
        val history =
            if (msg.content.output)
                rawHistory.map { case (session, line, input, output) => (session, line, Right((input, output))) }
            else
                rawHistory.map { case (session, line, input, output) => (session, line, Left(input)) }

        ipy.send(socket, msg.reply(MsgType.history_reply,
            history_reply(
                history=history)))
    }
}