(ns pocket-lisp.core
  "The entry-point for the Pocket Lisp CLI."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [pocket-lisp.parser :as parser]
            [pocket-lisp.interpreter :as interpreter]
            [instaparse.core :as insta])
  (:use [clojure.string :only [join]]
        [clojure.pprint :only [pprint]]
        [slingshot.slingshot :only [try+]])
  (:gen-class))


;; ## Constants
;; 
;; To avoid magic numbers and other data, we give them a name here.
(def cli-ok 0)
(def cli-error 1)


;; ## Project version and other meta-data
;;
;; These are stored in lein's `project.clj` so instead of duplicating them
;; here we just use a macro to bring them here at compile time.
(defmacro project-version []
  (let [[_ _ version & _] (read-string (slurp "project.clj"))]
    version))


;; ## Command-line
;;
;; Parsing and handling command line arguments.

(defn exit
  "Terminates the application with a particular exit status and message."
  [status msg]
  (println msg)
  (System/exit status))


(def cli-options
  [["-h" "--help" "Displays usage information and exits"]
   [nil "--version" "Displays the version and exits"]
   [nil "--ast" "Displays the AST for the file and exits"]
   [nil "--cst" "Displays the CST for the file and exits"]
   [nil "--max-native-stack-trace" "Defines the maximum number of locations to show in a native stack trace."
    :default 20]
   [nil "--[no-]trace-execution" "Prints a trace of the Pocket Lisp's interpreter execution"
    :default false]])


(defn usage 
  "Shows usage information for the command-line interface."
  [summary]
  (->> ["The Pocket Lisp interpreter!"
        ""
        "Usage: plisp [options] file.plisp"
        ""
        "Options:"
        summary
        ""
        "Examples:"
        ""
        "- Running a program:"
        "  $ plisp program.plisp"]
       (join \newline)))


(defn format-error-message
  "Constructs a proper error message when misusing the CLI."
  [errors]
  (->> ["Failed to run Pocket Lisp:"
        ""
        errors
        ""
        "---"
        "See `plisp --help` for detailed usage information."]
       (join \newline)))
    
       
(defn version-message
  "Outputs the version message for the Pocket Lisp."
  []
  (str "Pocket Lisp version " (project-version)))


(defn read-file
  "Reads a file with the given filename. Exits with a simple error message if not found."
  [filename]
  (try
    (slurp filename) 
    (catch java.io.FileNotFoundException e
      (exit cli-error (format-error-message (.getMessage e))))))


(defn make-command
  "Constructs a command-running function."
  [tag options file]
  (fn [commands]
    (if-let [command (tag commands)]
      (command options file)
      (exit cli-error (str "[internal error] No command defined for " tag)))))


(defn parse-command
  "Parses the command part of the command-line arguments."
  [options args]
  (if-let [file (first args)]
    (cond
      (:cst options)  (make-command :print-cst options file)
      (:ast options)  (make-command :print-ast options file)
      :else           (make-command :run options file))))


(defn run-cli
  "Parses command-line arguments and executes the specified action."
  [args actions]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        command (parse-command options arguments)]
    (cond
      (:help options)     (exit cli-ok (usage summary))
      errors              (exit cli-error (format-error-message errors))
      (:version options)  (exit cli-ok (version-message))
      command             (command actions)
      :else               (exit cli-error (usage summary)))))


(defn print-cst
  "Prints the CST for the given file."
  [options file]
  (let [source (read-file file)]
    (println "CST for " file "\n---")
    (let [cst (parser/pocket-parser source)]
      (if (insta/failure? cst)
        (exit cli-error (println (insta/get-failure cst)))
        (do
          (pprint cst)
          (exit cli-ok ""))))))


(defn print-ast
  "Prints the AST for the given file"
  [options file]
  (try+
    (println "AST for " file "\n---")
    (let [ast (parser/parse (read-file file))]
      (pprint ast))
    (catch [:type :pocket-lisp.parser/parse-error] {:keys [error]}
      (exit cli-error error))))


(defn run-program
  "Runs a program described by the given file"
  [options file]
  (try+
    (let [ast (parser/parse (read-file file))]
      (interpreter/with-options
        {:max-native-stack-length (:max-native-stack-length options)
         :trace-execution (:trace-execution options)}
        (interpreter/evaluate-many ast (interpreter/root-context)))
      (exit cli-ok ""))
    (catch [:type :pocket-lisp.parser/parse-error] {:keys [error]}
      (exit cli-error error))
    (catch [:type :pocket-lisp.interpreter/runtime-error] error
      (exit cli-error (interpreter/format-error error)))
    (catch Object e
      (exit cli-error (.getMessage e)))))


(defn -main
  "The Pocket Lisp command-line tool entry-point."
  [& args]
  (run-cli args 
    {:run run-program
     :print-ast print-ast
     :print-cst print-cst}))
  
