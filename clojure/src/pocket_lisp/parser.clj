(ns pocket-lisp.parser
  (:require [instaparse.core :as insta]
            [clojure.core.match :refer [match]])
  (:use [slingshot.slingshot :only [throw+]]))


(def pocket-parser
  (insta/parser
   "Program 
      = (_ Form)* _
     
    <Form>
      = DefineProc
      / Define
      / If
      / Quote
      / Lambda
      / Apply
      / number
      / string
      / bool
      / nil
      / name
       
    Define 
      = <'('> _ <define> _ name _ Form _ <')'>

    DefineProc 
      = <'('> _ <define> _ name _ Params _ Body _ <')'>

    If 
      = <'('> _ <if> _ Form _ Form _ Form _ <')'>

    Quote 
      = <'('> _ <quote> _ Form _ <')'>

    Lambda 
      = <'('> _ <lambda> _ Params _ Body _ <')'>

    Apply 
      = <'('> _ Form _ (_ Form)* _ <')'>
    
    Params
      = <'['> (_ name)* _ <']'>

    Body
      = (_ Form)*
    

    name
      = #'[a-zA-Z_\\-\\+\\*%=^?/\\><\\|\\\\][a-zA-Z0-9_\\-\\+\\*%=^?/\\><\\|\\\\]*'
    
    number
      = #'[0-9]+'

    string
      = #'\"[^\"]*\"'

    bool
      = 'true' | 'false'

    nil
      = <'nil'>

    <space> = #'\\s+'
    comment = #';[^\\n\\r]*(\\n|\\r)?'
    <_> = (<space> | comment)*
    define = 'define'
    if = 'if'
    quote = 'quote'
    lambda = 'lambda'
    "))
     

(defn source-location
  "Returns meta-data about the node's location."
  [value]
  (let [m (meta value)]
    {:span {:start-index (:instaparse.gll/start-index m)
            :end-index (:instaparse.gll/end-index m)}
     :start {:line (:instaparse.gll/start-line m)
             :column (:instaparse.gll/start-column m)}
     :end {:line (:instaparse.gll/end-line m)
           :column (:instaparse.gll/end-column m)}}))


(defn remove-comments
  "Removes comment nodes from the CST."
  [tree]
  (let [comment? (fn [x] (and (vector? x) (= (first x) :comment)))
        without-comments (fn [xs] (filter #(not (comment? %)) xs))]
    (insta/transform
      {:Program     (fn [& forms] (into [] (concat [:Program] (without-comments forms))))
       :Define      (fn [& forms] (into [] (concat [:Define] (without-comments forms))))
       :DefineProc  (fn [& forms] (into [] (concat [:DefineProc] (without-comments forms))))
       :If          (fn [& forms] (into [] (concat [:If] (without-comments forms))))
       :Quote       (fn [& forms] (into [] (concat [:Quote] (without-comments forms))))
       :Lambda      (fn [& forms] (into [] (concat [:Lambda] (without-comments forms))))
       :Apply       (fn [& forms] (into [] (concat [:Apply] (without-comments forms))))
       :Params      (fn [& forms] (into [] (concat [:Params] (without-comments forms))))
       :Body        (fn [& forms] (into [] (concat [:Body] (without-comments forms))))}
      tree)))

  
(defn cst->ast
  "Transforms a Concrete Syntax Tree (CST) for a Pocket Lisp program into an
   Abstract Syntax Tree (AST).
   
   Just like instaparse's CSTs, this AST has meta-data attached to each AST
   node describing its location (with the exception of language primitives)."
  [tree]
  (insta/transform
    {:Program      (fn [& forms] forms)
     :Define       (fn [name value] `(~'define ~name ~value))
     :DefineProc   (fn [name params body] `(~'define ~name ~params ~@body))
     :If           (fn [test then else] `(~'if ~test ~then ~else))
     :Quote        (fn [form] `(~'quote ~form))
     :Lambda       (fn [params body] `(~'lambda ~params ~@body))
     :Apply        (fn [callee & args] (cons callee args))
     :Params       (fn [& forms] forms)
     :Body         (fn [& forms] forms)
     :name         (fn [n] (symbol n))
     :number       (fn [n] (read-string n))
     :string       (fn [s] (read-string s))
     :bool         (fn [b] (read-string b))
     :nil          (fn [] nil)}
    tree))

     
(defn parse
  "Parses a string containing a Pocket Lisp program, and returns a Pocket Lisp AST.
  
   If parsing fails, throws an error with an instaparse failure."
  [source]
  (let [cst (pocket-parser source)]
    (if (insta/failure? cst)
      (throw+ {:type ::parse-error :error (insta/get-failure cst)})
      (->> cst
           (insta/add-line-and-column-info-to-metadata source)
           (remove-comments)
           (cst->ast)))))
