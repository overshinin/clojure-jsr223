package clojure.scripting;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;

import static javax.script.ScriptContext.ENGINE_SCOPE;
import static javax.script.ScriptContext.GLOBAL_SCOPE;

import clojure.lang.Associative;
import clojure.lang.Compiler;
import clojure.lang.IFn;
import clojure.lang.APersistentMap;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.Namespace;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;

/*
  ScriptEngineFactory
*/

public final class ClojureEngineFactory implements ScriptEngineFactory {

    private static final List<String> ENGINE_EXTS = Collections.unmodifiableList (Arrays.asList ("clj"));
    private static final List<String> ENGINE_MIME_TYPES = Collections.unmodifiableList (Arrays.asList ("application/clojure", "text/clojure"));
    private static final List<String> ENGINE_NAMES = Collections.unmodifiableList (Arrays.asList ("Clojure", "clojure"));

    private static final Map<String, String> ENGINE_PARAMS = new HashMap<String, String> ();

    static {
        String clojureName = "Clojure";
        String clojureVersion = RT.CLOJURE_NS.intern (Symbol.intern (null, "clojure-version")).invoke ().toString ();

        ENGINE_PARAMS.put (ScriptEngine.ENGINE, clojureName);
        ENGINE_PARAMS.put (ScriptEngine.ENGINE_VERSION, clojureVersion);
        ENGINE_PARAMS.put (ScriptEngine.NAME, clojureName);
        ENGINE_PARAMS.put (ScriptEngine.LANGUAGE, clojureName);
        ENGINE_PARAMS.put (ScriptEngine.LANGUAGE_VERSION, clojureVersion);
        ENGINE_PARAMS.put ("THREADING", "MULTITHREADED"); // ?? "THREAD-ISOLATED"
    }

    @Override
    public String getEngineName () {
        return ENGINE_PARAMS.get (ScriptEngine.ENGINE);
    }

    @Override
    public String getEngineVersion () {
        return ENGINE_PARAMS.get (ScriptEngine.ENGINE_VERSION);
    }

    @Override
    public List<String> getExtensions () {
        return ENGINE_EXTS;
    }

    @Override
    public String getLanguageName () {
        return ENGINE_PARAMS.get (ScriptEngine.LANGUAGE);
    }

    @Override
    public String getLanguageVersion () {
        return ENGINE_PARAMS.get (ScriptEngine.LANGUAGE_VERSION);
    }

    @Override
    public String getMethodCallSyntax (final String obj, final String m, final String... args) {
        StringBuilder sb = new StringBuilder ("(.").append (m).append (' ').append (obj);
        if (args != null)
            for (String arg : args)
                sb.append (' ').append (arg);
        return sb.append (")").toString ();
    }

    @Override
    public List<String> getMimeTypes () {
        return ENGINE_MIME_TYPES;
    }

    @Override
    public List<String> getNames () {
        return ENGINE_NAMES;
    }

    @Override
    public String getOutputStatement (final String toDisplay) {
        return new StringBuilder ("(println \"").append (toDisplay == null ? "null" : toDisplay.replace ("\"", "\\\"")).append ("\")").toString ();
    }

    @Override
    public Object getParameter (final String key) {
        return ENGINE_PARAMS.get (key);
    }

    @Override
    public String getProgram (final String... statements) {
        StringBuilder sb = new StringBuilder ("(do");
        if (statements != null)
            for (String statement : statements)
                sb.append (' ').append (statement);
        return sb.append (")").toString ();
    }

    @Override
    public ScriptEngine getScriptEngine () {
        return new ClojureEngine ();
    }

    /*
      Non-interface methods
    */

    /*
      All ScriptEngine operations must be executed in context of some Clojure Namespace.
      Template for Namespace name can be provided as JVM property (NS_TEMPLATE).
      If Template does not contain format specifiers (%d), all engines share single Namespace (NS_FORCED).
      Namespace can be global (NS_FORCED), created per ScriptEngine (default), or per ScriptContext (NS_PER_CONTEXT = false/false/true).
      In the NS_PER_CONTEXT mode, Namespace is stored in ScriptContext(ENGINE_SCOPE) Bindings under "javax.script.Namespace" key
      For the ScriptEngine methods, which do not accept ScriptContext, default (ScriptEngine) ScriptContext (and its namespace) is used.

      TODO: Namespace can be created per Bindings (NS_PER_BINDINGS)?
    */
    private static final String PACKAGE_NAME = ClojureEngineFactory.class.getPackage ().getName ();
    private static final String NS_TEMPLATE = System.getProperty (PACKAGE_NAME + ".NS_TEMPLATE", PACKAGE_NAME + ".ns-%d");
    private static final Namespace NS_FORCED = NS_TEMPLATE.equals (String.format (NS_TEMPLATE, 1)) ? createNamespace (NS_TEMPLATE) : null;
    private static final boolean NS_PER_CONTEXT = NS_FORCED == null && Boolean.getBoolean (PACKAGE_NAME + ".NS_PER_CONTEXT");
    private static final String NS_KEY = "javax.script.Namespace";

    // private static final Var REFER = RT.var ("clojure.core", "refer");
    // private static final Var WARN_ON_REFLECTION = RT.var ("clojure.core", "*warn-on-reflection*");

    private static final Var REFER = RT.CLOJURE_NS.intern (Symbol.intern (null, "refer"));
    private static final Var WARN_ON_REFLECTION = RT.CLOJURE_NS.intern (Symbol.intern (null, "*warn-on-reflection*"));
    // private static final Var ALLOW_UNRESOLVED_VARS = RT.CLOJURE_NS.intern (Symbol.intern (null, "*allow-unresolved-vars*"));

    private static Object callClojure (final Callable cc, final Associative tb) throws ScriptException {
        Var.pushThreadBindings (tb); // tb: ThreadBindings
        try {
            return cc.call (); // cc: ClojureCallable
            // TODO: Handle ScriptException: LineNumber, ColumnNumber
            // } catch (Compiler.CompilerException e) { // CompilerException extends RuntimeException
            //     throw (ScriptException) new ScriptException ("Unable to compile", e.source == null ? "<source>" : e.source, e.line).initCause (e);
        } catch (Exception e) {
            throw new ScriptException (e);
        } finally {
            Var.popThreadBindings ();
        }
    }

    // private static Associative addBindings (final Associative tb, final Bindings... bs) {
    //     Namespace ns = (Namespace) tb.valAt (RT.CURRENT_NS);
    //     Associative a = tb;
    //     for (Bindings b : bs)
    //         if (b != null)
    //             for (Map.Entry<String, Object> entry : b.entrySet ()) {
    //                 String k = entry.getKey ();
    //                 if (! k.startsWith ("javax.script.") && ! a.containsKey (k)) {
    //                     // Create dynamic typed VARs from scripting bindings
    //                     Var var = ns.intern (Symbol.intern (null, k)).setDynamic ();
    //                     Object v = entry.getValue ();
    //                     if (v != null)
    //                         var.setTag (Symbol.intern (null, v.getClass ().getName ()));
    //                     a = a.assoc (var, v);
    //                 }
    //             }
    //     return a;
    // }

    // private static Associative addBindings (final APersistentMap tb, final Bindings... bs) {
    //     Namespace ns = null;
    //     ATransientMap tm = null;
    //     for (Bindings b : bs)
    //         if (b != null)
    //             for (Map.Entry<String, Object> entry : b.entrySet ()) {
    //                 String k = entry.getKey ();
    //                 if ( ! (k.startsWith ("javax.script.") || (tm == null || tm.containsKey (k)) && tb.containsKey (k))) {
    //                     if (tm == null) {
    //                         ns = (Namespace) tb.valAt (RT.CURRENT_NS);
    //                         tm = PersistentHashMap.EMPTY.asTransient ();
    //                         for (Object o : tb.entrySet ()) {
    //                             Map.Entry e = (Map.Entry) o;
    //                             tm = (ATransientMap) tm.assoc (e.getKey (), e.getValue ());
    //                         }
    //                     }
    //                     // Create dynamic typed VARs from scripting bindings
    //                     Var var = ns.intern (Symbol.intern (null, k)).setDynamic ();
    //                     Object v = entry.getValue ();
    //                     if (v != null)
    //                         var.setTag (Symbol.intern (null, v.getClass ().getName ()));
    //                     tm = (ATransientMap) tm.assoc (var, v);
    //                 }
    //             }
    //     return tm != null ? tm.persistent () : tb;
    // }

    private static Associative addBindings (final APersistentMap tb, final Bindings... bs) {
        Namespace ns = null;
        Map<Var, Object> mb = null;
        for (Bindings b : bs)
            if (b != null)
                for (Map.Entry<String, Object> entry : b.entrySet ()) {
                    String k = entry.getKey ();
                    if ( ! (k.startsWith ("javax.script.") || tb.containsKey (k) || mb != null && mb.containsKey (k))) {
                        if (mb == null) {
                            ns = (Namespace) tb.valAt (RT.CURRENT_NS);
                            mb = new HashMap<Var, Object> ();
                        }
                        // Create dynamic typed VARs from scripting bindings
                        Var var = ns.intern (Symbol.intern (null, k)).setDynamic ();
                        Object v = entry.getValue ();
                        if (v != null)
                            var.setTag (Symbol.intern (null, v.getClass ().getName ()));
                        mb.put (var, v);
                    }
                }
        if (mb != null) {
            Object[] os = new Object [(tb.count () + mb.size ()) * 2];
            int i = 0;
            for (Object o : tb.entrySet ()) {
                Map.Entry e = (Map.Entry) o;
                os [i++] = e.getKey ();
                os [i++] = e.getValue ();
            }
            for (Map.Entry<Var, Object> e : mb.entrySet ()) {
                os [i++] = e.getKey ();
                os [i++] = e.getValue ();
            }
            return new PersistentArrayMap (os);
        }
        return tb;
    }

    private static Associative addBindings (final Object[] tbinit, final Bindings... bs) {
        Namespace ns = null;
        APersistentMap tb = new PersistentArrayMap (tbinit);
        Map<Var, Object> mb = null;
        for (Bindings b : bs)
            if (b != null)
                for (Map.Entry<String, Object> entry : b.entrySet ()) {
                    String k = entry.getKey ();
                    if ( ! (k.startsWith ("javax.script.") || tb.containsKey (k) || mb != null && mb.containsKey (k))) {
                        if (mb == null) {
                            ns = (Namespace) tb.valAt (RT.CURRENT_NS);
                            mb = new HashMap<Var, Object> ();
                        }
                        // Create dynamic typed VARs from scripting bindings
                        Var var = ns.intern (Symbol.intern (null, k)).setDynamic ();
                        Object v = entry.getValue ();
                        if (v != null)
                            var.setTag (Symbol.intern (null, v.getClass ().getName ()));
                        mb.put (var, v);
                    }
                }
        if (mb != null) {
            Object[] os = new Object [tbinit.length + mb.size () * 2];
            System.arraycopy (tbinit, 0, os, 0, tbinit.length);
            int i = tbinit.length;
            for (Map.Entry<Var, Object> e : mb.entrySet ()) {
                os [i++] = e.getKey ();
                os [i++] = e.getValue ();
            }
            return new PersistentArrayMap (os);
        } else
            return tb;
    }
    
    // private static IPersistentMap createClojureBindings (final IPersistentMap init, final Bindings... bs) {
    //     ITransientMap tm = init.asTransient ();
    //     Namespace ns = (Namespace) tm.valAt (RT.CURRENT_NS);
    //     for (Bindings b : bs)
    //         if (b != null)
    //             for (Map.Entry<String, Object> entry : b.entrySet ()) {
    //                 String k = entry.getKey ();
    //                 if (! k.startsWith ("javax.script.") && ! init.containsKey (k)) {
    //                     // Create dynamic typed VARs from scripting bindings
    //                     Var var = ns.intern (Symbol.intern (null, k)).setDynamic ();
    //                     Object v = entry.getValue ();
    //                     if (v != null)
    //                         var.setTag (Symbol.intern (null, v.getClass ().getName ()));
    //                     tm = tm.assoc (var, v);
    //                 }
    //             }
    //     return tm.persistent ();
    // }

    private static Callable asCompilerLoad (final Reader r) {
        if (r == null)
            throw new NullPointerException ("reader is null");

        return new Callable () {
            @Override
            public Object call () {
                return Compiler.load (r);
            }};
    }

    /*
      Namespaces support
    */
    private static Namespace createNamespace (final String name) {
        Namespace ns = Namespace.findOrCreate (Symbol.intern (null, name));
        try {
            callClojure (new Callable () {
                    @Override
                    public Object call () {
                        return REFER.invoke (RT.CLOJURE_NS.getName ());
                    }}, RT.mapUniqueKeys (RT.CURRENT_NS, ns));
        } catch (ScriptException e) {}
        return ns;
    }

    private static Namespace createNamespace () {
        return createNamespace (String.format (NS_TEMPLATE, RT.nextID ()));
    }

    /*
      ScriptEngine
    */

    private final class ClojureEngine implements ScriptEngine, Compilable, Invocable {

        /*
          ScriptEngine
        */

        @Override
        public Bindings createBindings () {
            return new SimpleBindings (new ConcurrentHashMap<String, Object> ());
        }

        @Override
        public Object eval (final Reader r) throws ScriptException {
            return callClojureA (asCompilerLoad (r));
        }

        @Override
        public Object eval (final Reader r, final Bindings b) throws ScriptException {
            return callClojureB (asCompilerLoad (r), b);
        }

        @Override
        public Object eval (final Reader r, final ScriptContext c) throws ScriptException {
            return callClojureC (asCompilerLoad (r), c);
        }

        @Override
        public Object eval (final String s) throws ScriptException {
            return eval (new StringReader (s));
        }

        @Override
        public Object eval (final String s, final Bindings b) throws ScriptException {
            return eval (new StringReader (s), b);
        }

        @Override
        public Object eval (final String s, final ScriptContext c) throws ScriptException {
            return eval (new StringReader (s), c);
        }

        @Override
        public Object get (final String k) {
            Bindings b = getBindings (ENGINE_SCOPE);
            return b != null ? b.get (k) : null;
        }

        @Override
        public Bindings getBindings (final int scope) {
            if (scope != GLOBAL_SCOPE && scope != ENGINE_SCOPE)
                throw new IllegalArgumentException("Invalid scope value.");

            return context.getBindings (scope);
        }

        @Override
        public ScriptContext getContext () {
            return context;
        }

        @Override
        public ScriptEngineFactory getFactory () {
            return ClojureEngineFactory.this;
        }

        @Override
        public void put (final String k, final Object v) {
            Bindings b = getBindings (ENGINE_SCOPE);
            if (b != null)
                b.put (k, v);
        }

        @Override
        public void setBindings (final Bindings b, final int scope) {
            if (scope != GLOBAL_SCOPE && scope != ENGINE_SCOPE)
                throw new IllegalArgumentException("Invalid scope value.");

            if (NS_PER_CONTEXT && scope == ENGINE_SCOPE)
                b.put (NS_KEY, getContextNS (context));

            context.setBindings (b, scope);
        }

        @Override
        public void setContext (final ScriptContext c) {
            if (c == null)
                throw new NullPointerException("context is null");

            if (NS_PER_CONTEXT) {
                Namespace ns = (Namespace) c.getAttribute (NS_KEY, ENGINE_SCOPE);
                if (ns == null) {
                    ns = createNamespace ();
                    c.setAttribute (NS_KEY, ns, ENGINE_SCOPE);
                }
                namespace = ns;
            }
            context = c;
        }

        /*
          Compilable
        */

        @Override
        public CompiledScript compile (final Reader r) throws ScriptException {
            return new ClojureCompiledScript (ClojureConcatReader.wrap ("(fn [] ", r, ")"));
        }

        @Override
        public CompiledScript compile (final String s) throws ScriptException {
            return compile (new StringReader (s));
        }

        /*
          CompiledScript
        */

        private final class ClojureCompiledScript extends CompiledScript implements Callable {

            private Object parsed;
            private IFn compiled;
            private Map<Namespace, IFn> compiledByNS;

            ClojureCompiledScript (final Reader r) throws ScriptException {
                ScriptContext c = ClojureEngine.this.context;
                Namespace ns = NS_PER_CONTEXT ? getContextNS (c) : ClojureEngine.this.namespace;

                callClojure (new Callable () {
                        @Override
                        public Object call () {
                            // parsed = LispReader.read (new LineNumberingPushbackReader (r), null); // from Clojure 1.7.0
                            parsed = LispReader.read (new LineNumberingPushbackReader (r), true, null, false); // Clojure 1.5.1
                            if (NS_PER_CONTEXT)
                                compiledByNS = new ConcurrentHashMap<Namespace, IFn> ();
                            try { // optionally; may fail due to missing vars/bindings, but try to use Engine/Global bindings
                                compiled = (IFn) Compiler.eval (parsed);
                                if (NS_PER_CONTEXT)
                                    compiledByNS.put ((Namespace) RT.CURRENT_NS.deref (), compiled);
                            } catch (Exception e) {}
                            return null;
                        }}, addBindings (new PersistentArrayMap (new Object [] {
                                    RT.CURRENT_NS, ns,
                                    RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref (),
                                    WARN_ON_REFLECTION, WARN_ON_REFLECTION.deref (),
                                    RT.READEVAL, RT.T,
                                    RT.DATA_READERS, RT.DATA_READERS.deref (),
                                    // ALLOW_UNRESOLVED_VARS, ALLOW_UNRESOLVED_VARS.deref (),
                                    Compiler.LOADER, RT.makeClassLoader (),
                                    Compiler.SOURCE_PATH, null,
                                    Compiler.SOURCE, "NO_SOURCE_FILE",
                                    Compiler.METHOD, null,
                                    Compiler.LOCAL_ENV, null,
                                    Compiler.LOOP_LOCALS, null,
                                    Compiler.NEXT_LOCAL_NUM, 0,
                                    Compiler.LINE_BEFORE, 1,
                                    Compiler.COLUMN_BEFORE, 1,
                                    Compiler.LINE_AFTER, 1,
                                    Compiler.COLUMN_AFTER, 1
                                }),
                            c.getBindings (ENGINE_SCOPE), c.getBindings (GLOBAL_SCOPE)));
            }

            @Override
            public Object eval () throws ScriptException {
                return callClojureA (this);
            }

            @Override
            public Object eval (final Bindings b) throws ScriptException {
                return callClojureB (this, b);
            }

            @Override
            public Object eval (final ScriptContext c) throws ScriptException {
                return callClojureC (this, c);
            }

            @Override
            public ScriptEngine getEngine () {
                return ClojureEngine.this;
            }

            @Override
            public Object call () { // implements Callable
                if (NS_PER_CONTEXT) {
                    Namespace ns = (Namespace) RT.CURRENT_NS.deref ();
                    IFn cfn = compiledByNS.get (ns);
                    if (cfn == null) {
                        cfn = (IFn) Compiler.eval (parsed);
                        compiledByNS.put (ns, cfn);
                    }
                    return cfn.invoke ();
                }
                if (compiled == null)
                    compiled = (IFn) Compiler.eval (parsed);
                return compiled.invoke ();
            }
        }

        /*
          Invocable
        */

        @Override
        public <T> T getInterface (final Class<T> clasz) {
            // Currently implemented just for completeness of Invocable.
            // Looks for functions with name "simpleClassName#method" for each interface's methods.
            return getClojureIF (null, clasz);
        }

        @Override
        public <T> T getInterface (final Object thiz, final Class<T> clasz) {
            // Currently implemented just for completeness of Invocable. Any object can be passed as an argument.
            // Looks for functions with name "simpleClassName#method" for each interface's methods.
            // When Interface's methods are invoked, "thiz" is passed as the first argument.
            if (thiz == null /*|| TODO: Object does not represent a scripting object */)
                throw new IllegalArgumentException ("Object is null or does not represent a scripting object");

            return getClojureIF (thiz, clasz);
        }

        @Override
        public Object invokeFunction (final String name, final Object... args) throws ScriptException, NoSuchMethodException {
            return callClojureF0 (getClojureFN (name), args);
        }

        @Override
        public Object invokeMethod (final Object thiz, final String name, final Object... args) throws ScriptException, NoSuchMethodException {
            if (thiz == null /*|| TODO: specified Object does not represent a scripting object */)
                throw new IllegalArgumentException ("Object is null or does not represent a scripting object");

            return callClojureF1 (getClojureFN (name), thiz, args);
        }

        /*
          Non-interface methods
        */

        // TODO: ? Rewrite ScriptContext, for ConcurrentHashMap as bindings
        private ScriptContext context = new SimpleScriptContext ();
        private Namespace namespace = NS_FORCED != null ? NS_FORCED : createNamespace ();

        {
            context.setBindings (createBindings (), ENGINE_SCOPE);
            if (NS_PER_CONTEXT)
                context.setAttribute (NS_KEY, namespace, ENGINE_SCOPE);
        }

        private Namespace getContextNS (final ScriptContext c) {
            Namespace ns = (Namespace) c.getAttribute (NS_KEY, ENGINE_SCOPE);
            return ns != null ? ns : namespace;
        }

        // private Object callClojureZ (final Callable cc, final Bindings b, final ScriptContext c) throws ScriptException {
        //     return callClojure (cc, addBindings (new PersistentArrayMap (new Object[] {
        //                     RT.CURRENT_NS, NS_PER_CONTEXT ? getContextNS (c) : namespace,
        //                     RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref (),
        //                     WARN_ON_REFLECTION, WARN_ON_REFLECTION.deref (),
        //                     // RT.IN, new LineNumberingPushbackReader (c.getReader ()),
        //                     RT.IN, c.getReader (),
        //                     RT.OUT, c.getWriter (),
        //                     RT.ERR, c.getErrorWriter ()}),
        //             b, c.getBindings (GLOBAL_SCOPE)));
        // }

        private Object callClojureZ (final Callable cc, final Bindings b, final ScriptContext c) throws ScriptException {
            return callClojure (cc, addBindings (new Object[] {
                            RT.CURRENT_NS, NS_PER_CONTEXT ? getContextNS (c) : namespace,
                            RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref (),
                            WARN_ON_REFLECTION, WARN_ON_REFLECTION.deref (),
                            // RT.IN, new LineNumberingPushbackReader (c.getReader ()),
                            RT.IN, c.getReader (),
                            RT.OUT, c.getWriter (),
                            RT.ERR, c.getErrorWriter ()},
                    b, c.getBindings (GLOBAL_SCOPE)));
        }

        private Object callClojureZ (final Callable cc, final ScriptContext c) throws ScriptException {
            return callClojureZ (cc, c.getBindings (ENGINE_SCOPE), c);
        }

        private IFn getClojureFN (final String name, final Namespace ns) throws NoSuchMethodException {
            if (name == null)
                throw new NullPointerException ("name is null");

            try {
                Symbol sym = Symbol.intern (name); // Maybe namespaced
                Var var = sym.getNamespace () != null ? Var.find (sym) : (Var) ns.getMapping (sym);
                IFn fn = (IFn) var.deref ();
                if (fn == null)
                    throw new NullPointerException ("IFn is null");
                return fn;
            } catch (Exception e) {
                throw (NoSuchMethodException) new NoSuchMethodException (name).initCause (e);
            }
        }

        private IFn getClojureFN (final String name) throws NoSuchMethodException {
            return getClojureFN (name, NS_PER_CONTEXT ? getContextNS (context) : namespace);
        }

        private Object callClojureF0 (final IFn fn, final Object... args) throws ScriptException {
            return callClojureZ (new Callable () {
                    @Override
                    public Object call () {
                        switch (args == null ? 0 : args.length) {
                        case 0: return fn.invoke ();
                        case 1: return fn.invoke (args[0]);
                        case 2: return fn.invoke (args[0], args[1]);
                        case 3: return fn.invoke (args[0], args[1], args[2]);
                        case 4: return fn.invoke (args[0], args[1], args[2], args[3]);
                        case 5: return fn.invoke (args[0], args[1], args[2], args[3], args[4]);
                        case 6: return fn.invoke (args[0], args[1], args[2], args[3], args[4], args[5]);
                        case 7: return fn.invoke (args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                        default: return fn.applyTo (RT.seq (args));
                        }
                    }}, context);
        }

        private Object callClojureF1 (final IFn fn, final Object thiz, final Object... args) throws ScriptException {
            return callClojureZ (new Callable () {
                    @Override
                    public Object call () {
                        switch (args == null ? 0 : args.length) {
                        case 0: return fn.invoke (thiz);
                        case 1: return fn.invoke (thiz, args[0]);
                        case 2: return fn.invoke (thiz, args[0], args[1]);
                        case 3: return fn.invoke (thiz, args[0], args[1], args[2]);
                        case 4: return fn.invoke (thiz, args[0], args[1], args[2], args[3]);
                        case 5: return fn.invoke (thiz, args[0], args[1], args[2], args[3], args[4]);
                        case 6: return fn.invoke (thiz, args[0], args[1], args[2], args[3], args[4], args[5]);
                        case 7: return fn.invoke (thiz, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                        default: return fn.applyTo (RT.cons (thiz, args));
                        }
                    }}, context);
        }

        @SuppressWarnings ("unchecked")
        private <T> T getClojureIF (final Object thiz, final Class<T> clasz) {
            if (clasz == null || ! clasz.isInterface ())
                throw new IllegalArgumentException ("Class object is null or is not an interface");

            Namespace ns = NS_PER_CONTEXT ? getContextNS (context) : namespace;
            String csn = clasz.getSimpleName ();
            final Map<String, IFn> fns = new HashMap<String, IFn> ();
            try {
                for (Method m : clasz.getMethods ()) // looks for "ClassName#methodName" in current ns
                    fns.put (m.getName (), getClojureFN (csn + "#" + m.getName (), ns));
            } catch (NoSuchMethodException e) {
                return null;
            }

            return (T) Proxy.newProxyInstance (RT.makeClassLoader (), new Class[] {clasz},
                                               new InvocationHandler () {
                                                   public Object invoke (final Object proxy, final Method method, final Object[] args) throws Throwable {
                                                       String name = method.getName ();
                                                       IFn fn = fns.get (name);
                                                       if (fn != null)
                                                           return thiz == null ? callClojureF0 (fn, args) : callClojureF1 (fn, thiz, args);
                                                       if ("toString".equals (name))
                                                           return "Proxy implementation of ClojureEngine/" + clasz.toString ();
                                                       if ("hashCode".equals (name))
                                                           return (thiz != null ? thiz : this).hashCode ();
                                                       if ("equals".equals (name))
                                                           return (thiz != null ? thiz : this).equals (args [0]);
                                                       throw new NoSuchMethodException (name);
                                                   }});
        }

        private Object callClojureA (final Callable cc) throws ScriptException {
            return callClojureZ (cc, context);
        }

        private Object callClojureB (final Callable cc, final Bindings b) throws ScriptException {
            if (b == null)
                throw new NullPointerException ("bindings is null");

            return callClojureZ (cc, b, context);
        }

        private Object callClojureC (final Callable cc, final ScriptContext c) throws ScriptException {
            if (c == null)
                throw new NullPointerException ("context is null");

            if (NS_PER_CONTEXT && c.getAttribute (NS_KEY, ENGINE_SCOPE) == null)
                c.setAttribute (NS_KEY, createNamespace (), ENGINE_SCOPE);

            return callClojureZ (cc, c);
        }
    }

    private static final class ClojureConcatReader extends Reader {

        private final Reader[] readers;
        private int pos = 0;

        private ClojureConcatReader (final Reader... rs) {
            readers = rs;
        }

        @Override
        public int read (final char[] cbuf, final int off, final int len) throws IOException {
            for (int res = -1; pos < readers.length; pos++)
                if ((res = readers[pos].read (cbuf, off, len)) != -1)
                    return res;
            return -1;
        }

        @Override
        public void close () throws IOException {
            for (Reader r : readers)
                r.close ();
        }

        static Reader wrap (final String before, final Reader r, final String after) {
            if (r == null)
                throw new NullPointerException ("reader is null");

            return new ClojureConcatReader (new StringReader (before), r, new StringReader (after));
        }
    }
}
