package beyond.plugin

import org.mozilla.javascript.commonjs.module.ModuleScope
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextAction
import org.mozilla.javascript.Function
import org.mozilla.javascript.tools.shell.Global
import org.mozilla.javascript.tools.shell.QuitAction

trait GamePlugin {
  // FIXME: Pass request parameters along with path and
  // make it possible to return the result asynchronously.
  def handle(path: String): String
}

// FIXME: Handle script errors.
object GamePlugin {
  private val contextFactory: BeyondContextFactory = new BeyondContextFactory

  // FIXME: Need a new class extending org.mozilla.javascript.TopLevel
  // because we don't need all powers of Global.
  private val global: Global = new Global

  // FIXME: Move implicit functions for Rhino interoperability to another package.
  private implicit def functionToQuitAction(f: (Context, Int) => Unit): QuitAction = {
    new QuitAction {
      override def quit(cx: Context, exitCode: Int) {
        f(cx, exitCode)
      }
    }
  }

  private implicit def functionToContextAction(f: Context => AnyRef): ContextAction = {
    new ContextAction {
      override def run(cx: Context): AnyRef = {
        f(cx)
      }
    }
  }

  def apply(): GamePlugin = {
    // FIXME: We don't want to allow plugins to terminate the system.
    // Delete this once we replace Global with our own global scope.
    global.initQuitAction {
      (_: Context, exitCode: Int) => System.exit(exitCode)
    }

    // FIXME: Don't hardcode plugin source code here.
    val source = "function handle(path) { return 'Hello ' + path; }"
    val cx: Context = contextFactory.enterContext()
    try {
      if (!global.isInitialized) {
        global.init(contextFactory)
      }
      // FIXME: Pass the module URI once we load scripts from file path.
      val scope = new ModuleScope(global, null, null)
      cx.evaluateString(scope, source, "source", 1, null)
      // FIXME: Don't hardcode the name of handler function.
      // FIXME: handler might be Scriptable.NOT_FOUND if there is no function named "handle".
      // Also, it might not be an instance of Function.
      val handler = scope.get("handle", scope).asInstanceOf[Function]
      new GamePluginImpl(scope, handler)
    } finally {
      Context.exit()
    }
  }

  private class GamePluginImpl(scope: ModuleScope, handler: Function) extends GamePlugin {
    def handle(path: String): String = {
      val result = contextFactory.call { cx: Context =>
        val args: Array[AnyRef] = Array(path)
        handler.call(cx, scope, scope, args)
      }
      result.toString
    }
  }
}


