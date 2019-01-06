# Pocket Lisp

Pocket Lisp is a toy Lisp dialect that can be easily implemented in any
programming language in a couple of days. But it's still a complete
application, so it's possible to evaluate tooling, developer experience,
support for data modelling and processing, and some of the I/O ecossystem.

## The language

Pocket Lisp has the following concrete syntax (in PEG):

```
Program
  = Form*

Form
  = "(" "define" name "[" name* "]" Form* ")"   -- procedure
  | "(" "define" name Form ")"                  -- define binding
  | "(" "if" Form Form Form ")"                 -- if
  | "(" "quote" Form ")"                        -- quotation
  | "(" Form Form* ")"                          -- application
  | number | string | bool | nil | name

number
  = digit+  -- only integers are allowed

string
  = /"[^"]*"/

bool
  = "true" | "false"

name
  = (letter | symbol) (letter | symbol | digit)*

letter = /[a-zA-Z]/
symbol = "!" | "%" | "*" | "-" | "_" | "+" | "=" | "^" | "?" | "/" | ">" | "<" | "|"
digit = /[0-9]/
```

And has the following AST:

```
x in Names
i in Integers
s in Strings
b in Booleans

Expr e ::= (define <x> <e1>)
         | (define <x> [<x1> ... <xn>] <e1> ... <en>)
         | (if <e1> <e2> <e3>)
         | (quote <e>)
         | (lambda [<x1> ... <xn>] <e1> ... <en>)
         | (<e1> ... <en>)
         | nil
         | x | i | s | b
```

Evaluation rules are straight-forward if you're familiar with the call-by-value lambda calculus. We use applicative-order for evaluating applications. Evaluation semantics are described below:

```
i, s, b, nil : value
(closure S [<x1> ... <xn>] <e1> ... <en>) : value

E[S, (define <x> <e1>)] ==> S <- S + {<x> = E[S, <e1>]}

E[S, (define <x> [<x1> ... <xn>] <e1> ... <en>)]
  ==> S <- S + {<x> = (closure S [<x1> ... <xn>] <e1> ... <en>)}

E[S, (if <e1> <e2> <e3>)] ==> (if E[S, <e1>] <e2> <e3>)
E[S, (if true <e2> <e3>)] ==> E[S, <e2>]
E[S, (if false <e2> <e3>)] ==> E[S, <e3>]

E[S, (quote <e1>)] ==> <e1>

E[S, (lambda [<x1> ... <xn>] <e1> ... <en>)]
  ==> (closure S [<x1> ... <xn>] <e1> ... <en>)

E[S{x = v}, x] ==> v

E[S1, (<e1> <e2> ... <en>)] ==> E[S1, (E[S1, <e1>] <e2> ... <en>)]
E[S1, ((closure S2 [<x1> ... <xn>] <e1_1> ... <e1_n>) <e2_1> ... <e2_n>)]
  ==> let S3 = S2 + {<x1> = E[S1, <e2_1>], ..., <xn> = E[S1, <e2_n>]}
      in E[S3, <e1_1>]; ...; E[S3, <e1_n>]
```

Where `E[S, form]` is the evaluation process, and `S` is a function mapping unique names to values.
