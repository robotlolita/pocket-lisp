(ns pocket-lisp.interpreter-test
  (:require [clojure.test :refer :all]
            [pocket-lisp.interpreter :refer :all])
  (:use [slingshot.slingshot :only [try+]]))


(deftest interpreting-literals
  (testing "Evaluating literals"
    (are [expr result] (= result (evaluate expr (root-context)))
         1000    1000
         10.5    10.5
         "foo"   "foo"
         true    true
         false   false
         'nil    nil)))


(deftest quotations
  (testing "Evaluating quotations"
    (are [expr result] (= result (evaluate expr (root-context)))
         '(quote (define a b))           '(define a b)
         '(quote (define a [b] c))       '(define a [b] c)
         '(quote (if a b c))             '(if a b c)
         '(quote (f a b c))              '(f a b c)
         '(quote (lambda [a] b))         '(lambda [a] b)
         '(quote a)                      'a
         '(quote 1000)                   1000
         '(quote "foo")                  "foo"
         '(quote true)                   true
         '(quote nil)                    nil)))


(deftest scoping
  (testing "Evaluating scoping-defining instructions"
    (let [ctx (root-context)]
      (evaluate '(define x 1) ctx)
      (= 1 (lookup ctx 'x))

      (= 1 (evaluate 'x ctx))

      (evaluate '(define f [x] x) ctx)
      (= (->CLambda ctx 'f ['x] '(x)) (lookup ctx 'f))

      (= (->CLambda ctx 'f ['x] '(x)) (evaluate 'f ctx)))))


(deftest branching
  (testing "Branching evaluation"
    (= 1 (evaluate '(if true 1 2) (root-context)))
    (= 2 (evaluate '(if false 1 2) (root-context)))))


(deftest application
  (testing "Functions must return their last argument"
    (let [ctx (root-context)]
      (evaluate '(define f [x y] x y) ctx)
      (= 2 (evaluate '(f 1 2) ctx))))

  (testing "Functions must evaluate all expressions in the body"
    (let [ctx (root-context)
          evaled (atom [])]
      (add-bindings! ctx {'touch (fn [x] 
                                  (swap! evaled conj x) 
                                  x)})
      (evaluate '(define f [x y z] (touch x) (touch y) (touch z)) ctx)
      (= 3 (evaluate '(f 1 2 3) ctx))

      (= [1 2 3] @evaled)))

  (testing "Lambdas must be proper closures"
    (let [ctx (root-context)]
      (add-bindings! ctx {'+ +})
      (evaluate '(define add [x] 
                   (lambda [y] 
                     (lambda [z]
                       (+ x y z)))) 
                ctx)
      (= 6 (evaluate '(((add 1) 2) 3) ctx)))))
      

(deftest error-conditions-and-traces
  (testing "Arity mismatch when applying functions"
    (let [ctx (root-context)]
      (evaluate '(define f [x y z] z) ctx)
      (evaluate '(define g [] (f 1 2)) ctx)
      (try+
        (evaluate '(g) ctx)
        (assert false "An exception should've been thrown")
        (catch [:type :pocket-lisp.interpreter/runtime-error :tag :arity-mismatch] error
          (is (= 2 (:given error)))
          (is (= 3 (:expected error)))
          (is (= '(g "<global>") (collect-stack-trace (:context error)))) 
          (is (= "#(f x y z) expects 3 arguments, got 2.\n  at g\n  at <global>"
                 (format-error error)))))))

  (testing "Undefined binding"
    (let [ctx (root-context)]
      (try+
        (evaluate 'x ctx)
        (assert false "An exception should've been thrown")
        (catch [:type :pocket-lisp.interpreter/runtime-error :tag :undefined-binding] error
          (is (= 'x (:name error)))
          (is (= '("<global>") (collect-stack-trace (:context error))))
          (is (= "x is not defined.\n  at <global>"
                 (format-error error)))))))

  (testing "Native errors"
    (let [ctx (root-context)]
      (add-bindings! ctx {'fail (fn [msg] (throw (Exception. msg)))})
      (try+
        (evaluate '(fail "oh no") ctx)
        (catch [:type :pocket-lisp.interpreter/runtime-error :tag :native-error] error
          (is (= "oh no" (.getMessage (:throwable error)))))))))
        

        
(deftest stack-traces
  (testing "Collecting stack traces"
    (let [ctx1 (root-context)
          ctx2 (make-context ctx1 "a")
          ctx3 (make-context ctx2 "b")]
      (is (= '("<global>") (collect-stack-trace ctx1)))
      (is (= '("a" "<global>") (collect-stack-trace ctx2)))
      (is (= '("b" "a" "<global>") (collect-stack-trace ctx3)))))
      
  (testing "Formatting stack traces"
    (let [ctx1 (root-context)
          ctx2 (make-context ctx1 "a")
          ctx3 (make-context ctx2 "b")]
      (is "at <global>"
          (= (format-stack-trace ctx1)))
      (is "at a\nat <global>"
          (= (format-stack-trace ctx2)))
      (is "at b\nat a\nat <global>"
          (= (format-stack-trace ctx3))))))
