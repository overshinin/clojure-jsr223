(set! *warn-on-reflection* true)

(ns clojure.scripting
  (:require (clojure [test :as t]))
  (:import (javax.script Bindings Compilable Invocable ScriptContext ScriptEngine ScriptEngineFactory
                         CompiledScript SimpleScriptContext ScriptEngineManager ScriptException)
           (java.io ByteArrayOutputStream OutputStreamWriter)))

;; (defmacro PRINT [o m & p]
;;   `(let [r# (try (. ~o ~m ~@p) (catch Exception e# e#))]
;;      (println (str '~o "." '~m '~p ":" (if (nil? r#) " nil" (format "(%s): %s" (type r#) r#))
;;                    (if (instance? Exception r#) (format ", caused by: %s" (.getCause ^Exception r#))))) r#))

(def TEST_VERBOSE (Boolean/getBoolean "test.verbose"))
;; (println "TEST_VERBOSE" TEST_VERBOSE)

(defmacro TRACE [o m & p]
  (if TEST_VERBOSE
    `(let [[a# b#] (try [(. ~o ~m ~@p) nil] (catch Exception e# [nil e#])) r# (or a# b#)]
       (println (str '~o "." '~m '~p ":"
                     (if r# (format "(%s): %s" (type r#) r#) " nil")
                     (if b# (format ", caused by: %s" (.getCause ^Exception b#)))))
       (if b# (throw b#) a#))
    `(. ~o ~m ~@p)))

;; (def CLOJURE-VERSION-STR (clojure-version))
;; (def CLOJURE-VERSION-STR (apply str (interpose "." ((juxt :major :minor :incremental) *clojure-version*))))
;; (def CLOJURE-VERSION-STR (clojure.string/join "." ((juxt :major :minor :incremental) *clojure-version*))) 

(def NS_TEMPLATE (System/getProperty "clojure.scripting.NS_TEMPLATE" "clojure.scripting.ns-%d"))
(def NS_IS_CONSTANT (= NS_TEMPLATE (format NS_TEMPLATE 1)))
(def NS_PER_CONTEXT (and (not NS_IS_CONSTANT) (System/getProperty "clojure.scripting.NS_PER_CONTEXT")))

;; (def DEFAULT_NS (.eval SE "*ns*"))

;; -------------------------------------------------- Factory (explicit class name)
(def ^ScriptEngineFactory SEF0 (clojure.scripting.ClojureEngineFactory.))

(t/is (= (TRACE SEF0 getEngineName) "Clojure"))
(t/is (= (TRACE SEF0 getEngineVersion) (clojure-version)))
(t/is (= (TRACE SEF0 getLanguageName) "Clojure"))
(t/is (= (TRACE SEF0 getLanguageVersion) (clojure-version)))
(t/is (= (TRACE SEF0 getExtensions) ["clj"]))
(t/is (= (TRACE SEF0 getMimeTypes) ["application/clojure" "text/clojure"]))
(t/is (= (TRACE SEF0 getNames) ["Clojure" "clojure"]))

(t/is (= (TRACE SEF0 getParameter ScriptEngine/ARGV) nil))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/ENGINE) "Clojure"))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/ENGINE_VERSION) (clojure-version)))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/FILENAME) nil))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/LANGUAGE) "Clojure"))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/LANGUAGE_VERSION) (clojure-version)))
(t/is (= (TRACE SEF0 getParameter ScriptEngine/NAME) "Clojure"))
(t/is (= (TRACE SEF0 getParameter "THREADING") "MULTITHREADED"))

(t/is (= (TRACE SEF0 getMethodCallSyntax "obj" "m" (into-array String ["arg1" "arg2"])) "(.m obj arg1 arg2)"))
(t/is (= (TRACE SEF0 getOutputStatement nil) "(println \"null\")"))
(t/is (= (TRACE SEF0 getOutputStatement "123") "(println \"123\")"))
(t/is (= (TRACE SEF0 getOutputStatement "'abc:\"ced\"'") "(println \"'abc:\\\"ced\\\"'\")"))
(t/is (= (TRACE SEF0 getProgram (into-array String ["(map identity (range 10))" "123"])) "(do (map identity (range 10)) 123)"))

;; (load-string (.getOutputStatement SEF0 nil))
;; (load-string (.getOutputStatement SEF0 "123"))
;; (load-string (.getOutputStatement SEF0 "'abc:\"ced\"'"))

;; -------------------------------------------------- Engine (via ScriptEngineManager)
(def ^ScriptEngineManager SEM (ScriptEngineManager.))

(println (.getEngineFactories SEM))

(def ^ScriptEngine SE (.getEngineByName SEM "Clojure"))
;; (def ^ScriptEngine SE (.getEngineByExtension SEM "clj"))
(def ^Compilable SEC SE)
(def ^Invocable SEI SE)


(def ^Bindings SEB (.createBindings SE))
(def ^ScriptContext SSE (SimpleScriptContext.))

(def ^ScriptEngineFactory SEF (.getFactory SE))

(def DEFAULT_NS (TRACE SE eval "*ns*"))


;; TEST simple eval, good or bad, with bindings and context
;; TEST compile, good or bad

(def ^CompiledScript CS1 (t/is (as-> (TRACE SEC compile "*ns*") $ (if (instance? CompiledScript $) $))))
(def ^CompiledScript CS2 (t/is (as-> (TRACE SEC compile "(+ 2 2)") $ (if (instance? CompiledScript $) $))))
(def ^CompiledScript CS3 (t/is (as-> (TRACE SEC compile "(println (+ 2 2))") $ (if (instance? CompiledScript $) $))))
(def ^CompiledScript CS4 (t/is (as-> (TRACE SEC compile "(+ x y)") $ (if (instance? CompiledScript $) $))))
(def ^CompiledScript CS5 (t/is (as-> (TRACE SEC compile "(let [r (+ x y) ] (println r) [*ns* r])") $ (if (instance? CompiledScript $) $))))

(t/is (thrown-with-msg? ScriptException #"EOF while reading"
                        (TRACE SEC compile "(+ 2 2")))

;; TEST eval of compiled, good or bad, with bindings and context







;; (TRACE CS eval SEB)
;; (TRACE CS eval SEB)

(t/is (= 4 (TRACE CS2 eval)))

;; (TRACE CS4 eval)

;; (time (.eval SE "(+ 2 2)"))
;; (time (.compile SEC "(+ 2 2) (+ 3 3)"))
;; (time (.eval CS2))

(let [b (doto (.createBindings SE) (.put "x" 12) (.put "y" 30))
      s1 (time (.compile SEC "(+ 2 2) (+ 3 3)"))
      s2 (time (.compile SEC "(+ y x)"))
      ;; s2 (time (.compile SEC "(defn mul [a b] (* a b)) (mul x y) (#(+ % y) (mul x y))"))
      ]
  (time (.eval s1 b))
  (time (.eval s1 b))
  (time (.eval s1 b))
  (time (.eval s1 b))
  (time (.eval s1 b))
  ;; (time (try (.eval s2) (catch Exception e (println "ex" e))))
  ;; (time (try (.eval s2) (catch Exception e (println "ex" e))))
  ;; (time (try (.eval s2) (catch Exception e (println "ex" e))))
  (time (.eval s2 b))
  (time (.eval s2 b))
  (time (.eval s2 b))
  (time (.eval s2 b))
  (time (.eval s2 b))
  (println "(.eval s2 b):" (.eval s2 b))
  )

(TRACE CS4 eval (doto (.createBindings SE) (.put "x" 12) (.put "y" 30)))


;; TEST invokeFunction, good or bad
;; TEST getInterface and invoke method, good or bad


(TRACE SE eval "(defn Callable#call [] (println :callable) :callable)")

(def ^Callable cxx (TRACE SEI getInterface Callable))
(TRACE cxx call)
(TRACE cxx equals SEI)
(TRACE cxx hashCode)
(TRACE cxx toString)
(println cxx)

;; TEST namespaces




;; -------------------------------------------------- Namespaces
;; (if NS_IS_CONSTANT
;;   (t/is (= DEFAULT_NS (clojure.lang.Namespace/findOrCreate (clojure.lang.Symbol/intern NS_TEMPLATE)))))

(if NS_IS_CONSTANT
  (t/is (= DEFAULT_NS (clojure.lang.Namespace/findOrCreate (symbol NS_TEMPLATE)))))

(if NS_PER_CONTEXT
  (do
    (let [NS_SSE_E (TRACE SE eval "*ns*" SSE)
          NS_NEW1_E (TRACE SE eval "*ns*" (SimpleScriptContext.))
          NS_NEW2_E (TRACE SE eval "*ns*" (SimpleScriptContext.))
          NS_SSE_C (TRACE CS1 eval SSE)
          NS_NEW1_C (TRACE CS1 eval (SimpleScriptContext.))
          NS_NEW2_C (TRACE CS1 eval (SimpleScriptContext.))]
      (t/is (= DEFAULT_NS (TRACE SE eval "*ns*")))
      (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" SEB)))
      (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" (.createBindings SE))))
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*" SSE)))
      (t/is (not= DEFAULT_NS NS_SSE_E))
      (t/is (not= DEFAULT_NS NS_NEW1_E))
      (t/is (not= NS_SSE_E NS_NEW1_E))
      (t/is (not= DEFAULT_NS NS_NEW2_E))
      (t/is (not= NS_SSE_E NS_NEW2_E))
      (t/is (not= NS_NEW1_E NS_NEW2_E))
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*" SSE)))
      (t/is (not= NS_NEW1_E (TRACE SE eval "*ns*" SSE)))
      (t/is (not= NS_NEW2_E (TRACE SE eval "*ns*" SSE)))
      (t/is (= DEFAULT_NS (TRACE CS1 eval)))
      (t/is (= DEFAULT_NS (TRACE CS1 eval SEB)))
      (t/is (= DEFAULT_NS (TRACE CS1 eval (.createBindings SE))))
      (t/is (= NS_SSE_C (TRACE CS1 eval SSE)))
      (t/is (not= DEFAULT_NS NS_SSE_C))
      (t/is (not= DEFAULT_NS NS_NEW1_C))
      (t/is (not= NS_SSE_C NS_NEW1_C))
      (t/is (not= DEFAULT_NS NS_NEW2_C))
      (t/is (not= NS_SSE_C NS_NEW2_C))
      (t/is (not= NS_NEW1_C NS_NEW2_C))
      (t/is (= NS_SSE_C (TRACE CS1 eval SSE)))
      (t/is (not= NS_NEW1_C (TRACE CS1 eval SSE)))
      (t/is (not= NS_NEW2_C (TRACE CS1 eval SSE)))
      (.setContext SE SSE)
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*")))
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*" SEB)))
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*" (.createBindings SE))))
      (t/is (= NS_SSE_E (TRACE SE eval "*ns*" SSE)))
      (t/is (= NS_SSE_C (TRACE CS1 eval)))
      (t/is (= NS_SSE_C (TRACE CS1 eval SEB)))
      (t/is (= NS_SSE_C (TRACE CS1 eval (.createBindings SE))))
      (t/is (= NS_SSE_C (TRACE CS1 eval SSE)))
      )
    )
  (do
    (t/is (= DEFAULT_NS (TRACE SE eval "*ns*")))
    (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" SEB)))
    (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" (.createBindings SE))))
    (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" SSE)))
    (t/is (= DEFAULT_NS (TRACE SE eval "*ns*" (SimpleScriptContext.))))
    (t/is (= DEFAULT_NS (TRACE CS1 eval)))
    (t/is (= DEFAULT_NS (TRACE CS1 eval SEB)))
    (t/is (= DEFAULT_NS (TRACE CS1 eval (.createBindings SE))))
    (t/is (= DEFAULT_NS (TRACE CS1 eval SSE)))
    (t/is (= DEFAULT_NS (TRACE CS1 eval (SimpleScriptContext.))))
    ))



(let [args (into-array Object [2 2])]
  (time (dotimes [i 1000000]
          (.invokeFunction SEI "+" args))))

(let [b (doto ^Bindings (.createBindings SE) (.put "x" 2) (.put "y" 2))]
  (time (dotimes [i 1000000]
          (.eval CS4 b))))

(let [b (doto ^Bindings (.createBindings SE) (.put "x" 2) (.put "y" 2)
              (.put "a" 2) (.put "b" 2) (.put "c" 2) (.put "d" 2) (.put "e" 2) (.put "f" 2)
              )]
  (time (dotimes [i 1000000]
          (.eval CS4 b))))


(System/exit 0)

;; (t/is (= (type (.eval SE "*ns*")) (type *ns*)))
;; (t/is (= (.eval SE "(+ 2 2)") 4))

;; (t/is (fn? (as-> (.eval SE "(defn four [_ _] (+ 2 2))") $ (if (var? $) (deref $)))))

;; (t/is (thrown-with-msg? ScriptException #"Unable to resolve symbol: x in this context" (.eval SE "(defn bad [] \n (+ \n 2 \n 2 \n x))")))
;; (t/is (thrown-with-msg? ScriptException #"EOF while reading" (.eval SE "(defn bad-not-read [] ((((")))
;; (t/is (thrown-with-msg? ScriptException #"java\.lang\.ArithmeticException: Divide by zero" (.eval SE "(/ 1 0)")))



;; (def ^ByteArrayOutputStream baos (ByteArrayOutputStream.))

;; (def ^ScriptContext SSE1 (doto (SimpleScriptContext.)
;;                            (.setWriter (OutputStreamWriter. baos))))

;; (def ^Bindings SEB1 (doto (.createBindings SE)
;;                       (.put "x" "XYZ")
;;                       (.put "*out*" baos)
;;                       ))


;; (PRINT SE eval "(println (+ 2 2))" SSE1)

;; (println "Content of BAOS:" (.toString baos))


;; ;; (PRINT SE eval "(defn four [_ _] (+ 2 2))")
;; ;; (PRINT SE eval "(defn reflect [x y] (+ (.intValue x) (.longValue y)))")
;; ;; (PRINT SE eval "(defn bad [] \n (+ \n 2 \n 2 \n x))")
;; ;; (PRINT SE eval "(defn Callable#call [] (println :callable) :callable)")
;; ;; (PRINT SE eval "(/ 1 0)")
;; ;; (PRINT SE eval "(def x nil)")


;; (as-> #'+ $ (and (var? $) (deref $)) (fn? $))
















;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^ScriptEngineManager SEM (ScriptEngineManager.))
(def ^ScriptEngine SE (.getEngineByName SEM "Clojure"))
(def ^ScriptEngineFactory SEF (.getFactory SE))

(def ^Bindings SEB (.createBindings SE))

(println "----------" (type SE))
(TRACE SE eval "[*ns*]")
(TRACE SE eval "(+ 2 2)")
(TRACE SE eval "(println (+ 2 2))")
(TRACE SE eval "(defn four [_ _] (+ 2 2))")
(TRACE SE eval "(defn reflect [x y] (+ (.intValue x) (.longValue y)))")
(TRACE SE eval "(defn bad [] \n (+ \n 2 \n 2 \n x))")
(TRACE SE eval "(defn Callable#call [] (println :callable) :callable)")
(TRACE SE eval "(/ 1 0)")
(TRACE SE eval "(def x nil)")

(TRACE SE eval "[*ns*]" SEB)

(def ^Compilable SEC SE)

(println "----------" (type SEC))
(def ^CompiledScript CS
  (TRACE SEC compile "(do (println \"NAMESPACE IS :::\" *ns* \":::\") (+ 2 2))"))

(println "----------" (type CS))
(TRACE CS eval)
(TRACE CS eval SEB)
(TRACE CS eval SEB)

(def ^Invocable SEI SE)
(println "----------" (type SEI))
(TRACE SEI invokeFunction "four" (into-array Object [2 2]))
(TRACE SEI invokeFunction "+" (into-array Object [2 2]))
(TRACE SEI invokeFunction "clojure.core/+" (into-array Object [2 2]))

(TRACE SEI invokeFunction "clojure.cor/+" (into-array Object [2 2]))
(TRACE SEI invokeFunction "clojure.core/++" (into-array Object [2 2]))
(TRACE SEI invokeFunction "*ns*" (into-array Object [2 2]))
(TRACE SEI invokeFunction "x" (into-array Object [2 2]))
(TRACE SEI invokeFunction "" (into-array Object [2 2]))
(TRACE SEI invokeFunction nil (into-array Object [2 2]))
(TRACE SEI invokeFunction "nil?" (into-array Object [2 2]))

(TRACE SEI invokeFunction "reflect" (into-array Object [2 2]))

;;(TRACE SEI getInterface Callable)

(def ^Callable cxx
  (TRACE SEI getInterface Callable))
(TRACE cxx call)
(TRACE cxx equals SEI)
(TRACE cxx hashCode)
(TRACE cxx toString)
(println cxx)

(let [args (into-array Object [2 2])]
  (time (dotimes [i 1000000]
          (.invokeFunction SEI "+" args))))

(def ^ScriptContext SSE (SimpleScriptContext.))

(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (SimpleScriptContext.))
(TRACE SE eval "*ns*" (SimpleScriptContext.))

(.setContext SE SSE)

(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (SimpleScriptContext.))
(TRACE SE eval "*ns*" (SimpleScriptContext.))

(.setContext SE (SimpleScriptContext.))

(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (SimpleScriptContext.))
(TRACE SE eval "*ns*" (SimpleScriptContext.))

(.setContext SE SSE)

(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*")
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (.createBindings SE))
(TRACE SE eval "*ns*" (SimpleScriptContext.))
(TRACE SE eval "*ns*" (SimpleScriptContext.))




;; ArityException
;; (defn arity1 [x] x) ;; (arity1 1 2) - fail to execute
;; (defn arity2 [x y] (arity1 x y)) ;; (arity2 1 2) - fail to execute arity1
;; (defn arity3 [x y z] (arity3 x y)) ;; fail to recursive execute arity3

#_(println SE)




;; user> (defn Callable#call [] 1 )
;; #'user/Callable#call
;; user> (defn Callable.call [] 1 )
;; #'user/Callable.call
;; user> (defn Callable$call [] 1 )
;; #'user/Callable$call
;; user> (defn Callable%call [] 1 )
;; #'user/Callable%call
;; user> (defn Callable-call [] 1 )
;; #'user/Callable-call
;; user> (defn Callable:call [] 1 )
;; #'user/Callable:call
;; user> (defn Callable!call [] 1 )
;; #'user/Callable!call
;; user> (defn Callable&call [] 1 )
;; #'user/Callable&call
;; user> (defn Callable*call [] 1 )
;; #'user/Callable*call
;; user> (defn Callable+call [] 1 )
;; #'user/Callable+call
;; user> (defn Callable=call [] 1 )
;; #'user/Callable=call
;; user> (defn Callable|call [] 1 )
;; #'user/Callable|call
;; user> (defn Callable>call [] 1 )
;; #'user/Callable>call
;; user> (defn Callable<call [] 1 )
;; #'user/Callable<call
;; user> (defn Callable?call [] 1 )
;; #'user/Callable?call
;; user> 

;(binding [x 2] (let [x 1] (fn [] x)))

(binding [*allow-unresolved-vars* true] (fn [] x))

*allow-unresolved-vars* true

(alter-var-root #'*allow-unresolved-vars* (fn [_] false))

(alter-var-root #'*allow-unresolved-vars* (fn [_] true))

(fn [] (fn [] x))

x
