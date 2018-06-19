## CLOJURE-JSR223

This is an implementation of JSR 223 (Java Scripting API) for Clojure.

Requires Java 5+, Clojure 1.5.1+ (the latest version, which was compiled using Java 5)

#### To build
- create `lib` directory and put https://repo1.maven.org/maven2/org/clojure/clojure/1.9.0/clojure-1.9.0.jar there,
  or https://repo1.maven.org/maven2/org/clojure/clojure/1.5.1/clojure-1.5.1.jar + `script-api.jar` from https://www.jcp.org/en/jsr/detail?id=223 (to build for Java 5)
- optionally set environment variable(s) (`$JAVA_HOME_5`, `$JAVA_HOME_6`, `$JAVA_HOME_7`)
- use `ant`

#### To test
- put http://central.maven.org/maven2/org/clojure/core.specs.alpha/0.1.24/core.specs.alpha-0.1.24.jar and http://central.maven.org/maven2/org/clojure/spec.alpha/0.1.143/spec.alpha-0.1.143.jar into `lib` directory
  or use `ant` target `runtest5`
- use `ant`

#### Implementation notes

##### Namespaces

By default, each new `ScriptEngine` is created in new Clojure Namespace, template for Namespace name is `clojure.scripting.ns-%d`.
"Template" may be overrridden, via JVM argument, for example `-Dclojure.scripting.NS_TEMPLATE=c.s.n%d`
If "template" does not contain "format specifier" (like `%d`), all `ScriptEngine`s share single Clojure Namespace.
For example, passing `-Dclojure.scripting.NS_TEMPLATE=user` to JVM enforces all ScriptEngines to use default Namespace (`user`).

Clojure Namespace can also be created per `ScriptContext`, if `-Dclojure.scripting.NS_PER_CONTEXT=true` is passed to JVM.
In this case, Namespace name is stored in `ScriptContext(ENGINE_SCOPE)'s Bindings` under `javax.script.Namespace` key.

##### `javax.script.Compilable`

"Compilation" is implemented via creating and evaluating new function, which contains passed script. 
All symbols, used in script, must be bound (via Bindings, or Clojure root variables) at the moment of `CompiledScript.eval ()`.

##### `javax.script.Invocable`

`Invocable.getInterface ()` expects existing set of functions in the current Namespace, with names `SimpleClassName#methodName`
