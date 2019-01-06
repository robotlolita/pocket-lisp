(ns pocket-lisp.interpreter
  "The core portion of the Pocket Lisp interpreter.
   Defines runtime types and interpretation routines."
  (:require [clojure.core.match :refer [match]]
            [clojure.stacktrace :as stack]
            [pocket-lisp.prelude :as prelude])
  (:use [slingshot.slingshot :only [throw+ try+]]
        [clojure.string :only [join]]))


;; Clojure has a hard time with recursive declarations so we must
;; forward-declare the evaluator here.
(declare evaluate)
(declare evaluate-many)


;; ## Configurable aspects
;;
;; Some aspects of the interpreter can be configured. Instead of having
;; these pieces of configuration be threaded through every function call,
;; we use dynamically-scoped bindings.
;;
(def ^:dynamic *max-native-stack-length* 20)
(def ^:dynamic *trace-execution* false)


(defmacro with-options
  "Executes body with the given options"
  [{:keys [max-native-stack-length trace-execution]
    :or {max-native-stack-length *max-native-stack-length*
         trace-execution *trace-execution*}}
   body]
  `(with-bindings
    {#'*max-native-stack-length* ~max-native-stack-length
     #'*trace-execution* ~trace-execution}
    ~body))


(defn trace
  "Prints something to the execution trace, if enabled."
  [& options]
  (if *trace-execution*
    (apply println "[TRACE]" options)))


;; ## Abstract Syntax
;;
;; The AST is implicitly described by lists of symbols, as it's common
;; in Lisp dialects.
;;
;; The AST used by this interpreter is as follows:
;;
;;     Expr e ::= (define <x> <e1>)                             ; binding definition
;;              | (define <x> [<x1> ... <xn>] <e1> ... <en>)    ; procedure definition
;;              | (if <e1> <e2> <e3>)                           ; conditional
;;              | (quote <e1>)                                  ; quotation
;;              | (lambda [<x1> ... <xn>] <e1> ... <en>)        ; lambda value
;;              | (<e1> ... <en>)                               ; application
;;              | (symbol x)                                    ; variable dereference
;;              | (number i) | (string s) | (bool b) | nil      ; literals


;; ## Context
;; 
;; The interpreter has an evaluation context, which includes things
;; like which function we're evaluating (so we can construct stack
;; traces), the local environment record, etc.
;;

(defprotocol IContext
  "Operations on an evaluation context."

  (context-location [self]
    "Returns the Location for the context--used for stack traces.")

  (context-parent [self]
    "Returns the parent of the context.")

  (add-bindings! [self bindings]
    "Adds possibly-several bindings to the context.")

  (lookup [self name]
    "Looks up a name in a context."))


(deftype Context [parent location env]
  IContext
  (add-bindings! [self bindings]
    (swap! env conj bindings)
    nil)

  (context-location [self]
    location)

  (context-parent [self]
    parent)

  (lookup [self name]
    (if-let [value (get @env name)]
      value
      (if (not (nil? parent))
        (lookup parent name)))))

        
(defn make-context 
  "Constructs a new context, inheriting from a parent context, and with the given location."  
  [parent name]
  (Context. parent name (atom {})))


(defn root-context 
  "Constructs a new global context."
  []
  (let [ctx (make-context nil "<global>")]
    (add-bindings! ctx prelude/globals)
    ctx))


(defn collect-stack-trace 
  "Given a context, returns a sequence representing its stack trace."
  [context]
  (cons (context-location context)
        (if-let [parent (context-parent context)]
          (collect-stack-trace parent))))


(defn format-stack-trace 
  "Given a context, returns a formatted string representing its stack trace."
  [context]
  (->> (collect-stack-trace context)
       (map #(str "  at " %))
       (join "\n")))


(defn show-lambda
  "Provides a good representation of a lambda"
  [{:keys [name params]}]
  (str "#(" name " " (join " " params) ")"))


(defn format-error 
  "Given an error message, returns a formatted string describing it, with a stack trace."
  [error]
  (case (:tag error)
    :arity-mismatch  (do
                       (str (show-lambda (:lambda error)) 
                            " expects " (:expected error) 
                            " arguments, got " (:given error) ".\n" 
                            (format-stack-trace (:context error))))

    :native-error (let [{:keys [throwable context]} error]
                    (str (-> throwable .getClass .getName) ": " (.getMessage throwable) "\n" 
                      (format-stack-trace context)
                      "\n\nNative trace:\n"
                      (with-out-str (stack/print-stack-trace throwable *max-native-stack-length*))))

    :undefined-binding (str (:name error) " is not defined.\n"
                            (format-stack-trace (:context error)))))


;; ## Runtime values
;;
;; Pocket Lisp reuses Clojure's types where possible, but lambdas would be
;; weird to represent as Clojure's own functions. Luckily we can
;; define a new type *and* extend Clojure's application rules to it
;; so their use is indistinguishable from Clojure's own functions.
;;

(defprotocol ILambda
  "Any procedure that can be called in Pocket Lisp."
  (lambda-arity [self]
    "The number of arguments this procedure accepts.")

  (apply-lambda [self args]
    "Applies this procedure to the list of arguments given."))


(defrecord CLambda [context name params body]
  ILambda
  (lambda-arity [self]
    (count params))

  (apply-lambda [self args]
    (let [new-context (make-context context name)]
      (add-bindings! new-context (zipmap params args))
      (evaluate-many body new-context)))

  clojure.lang.IFn
  (applyTo [self args]
    (assert (= (count args) (count params)) 
      (str "Arity mismatch: " name "/" (count params)))
    (apply-lambda self args)))
    
  

;; ## Interpreter
;;
;; The interpreter just walks the tree, evaluating every operation node.
;; A context type is used to keep information about the evaluation, such
;; as scope and execution trace--which allows stack traces to be generated.
;;

(defn evaluate 
  "Evaluates the given expression in the context, by walking down the tree."
  [expr context]
  (if *trace-execution*
    (trace expr "\n in " (context-location context)))
  
  (match [expr]
    [(['define name expr1] :seq)]
    (do
      (trace "define " name)
      (let [value (evaluate expr1 context)]
        (trace name "=" value)
        (add-bindings! context {name value})
        value))

    [(['define name params & body] :seq)]
    (do
      (trace "define procedure " name params)
      (add-bindings! 
        context 
        {name (CLambda. context name params body)}))

    [(['if test consequent alternate] :seq)]
    (if (evaluate test context)
      (do
        (trace "evaluating consequent")
        (evaluate consequent context))
      (do
        (trace "evaluating alternate")
        (evaluate alternate context)))

    [(['quote form] :seq)]
    (do
      (trace "quote of " form)
      form)

    [(['lambda params & body] :seq)]
    (do
      (trace "createing a closure in " (context-location context))
      (CLambda. context "lambda" params body))
    
    [([callee-node & args-node] :seq)]
    (do
      (trace "applying " callee-node " to " args-node)
      (let [callee (evaluate callee-node context)
            args (map #(evaluate % context) args-node)]
        (trace "evaluated callee:" callee "\nevaluated args:" args)
        (if (satisfies? ILambda callee)
          (do
            (trace "Pocket Lisp lambda" callee)
            (if (= (count args) (lambda-arity callee))
              (apply-lambda callee args)
              (throw+ {:type ::runtime-error
                       :tag :arity-mismatch
                       :lambda callee
                       :expected (lambda-arity callee)
                       :given (count args)
                       :context context})))
          (try+
            (trace "Applying closure IFn" callee)
            (apply callee args)
            (catch [:type ::runtime-error] _
              (throw+))
            (catch Exception e
              (throw+ {:type ::runtime-error
                       :tag :native-error
                       :throwable e
                       :context context}))))))

    [name :guard #(symbol? %)]
    (do
      (trace "Loading binding " name)
      (if-let [value (lookup context name)]
        value
        (throw+ {:type ::runtime-error
                 :tag :undefined-binding
                 :name name
                 :context context})))

    [nil]
    nil

    [lit :guard #(or (number? %) (string? %) (boolean? %))]
    lit))


(defn evaluate-many 
  "Evaluates the given list of expressions in the context, returning the
   result of evaluating the last one."
  [exprs context]
  (reduce (fn [_ expr] (evaluate expr context))
          nil
          exprs))
