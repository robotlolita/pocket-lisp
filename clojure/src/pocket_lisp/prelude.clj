(ns pocket-lisp.prelude
  "Built-in functions for Pocket Lisp")


(def globals
  ;; I/O functions
  {'display println
   'read-file slurp
   'write-file spit

   ;; Relational & Equality
   '= =
   'not= not=
   '> >
   '>= >=
   '< <
   '<= <=

   ;; Maths
   '+ +
   '- -
   '* *
   '/ /
   'number? number?

   ;; Booleans
   'not (fn [x] (not x))
   'and (fn [& xs] (reduce #(and %1 %2) xs))
   'or (fn [& xs] (reduce #(or %1 %2) xs))

   ;; Strings
   'string-join clojure.string/join
   'string-split clojure.string/split
   'string-upcase clojure.string/upper-case
   'string-downcase clojure.string/lower-case
   'string-reverse clojure.string/reverse
   'string-trim clojure.string/trim
   'starts-with? clojure.string/starts-with?
   'ends-with? clojure.string/ends-with?

   ;; Lists
   'first first
   'rest rest
   'nth nth
   'take take
   'drop drop
   'count count
   'map map
   'filter filter
   'reduce reduce
   'flatmap mapcat})


