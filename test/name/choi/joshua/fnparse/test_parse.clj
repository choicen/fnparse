(ns name.choi.joshua.fnparse.test-parse
  (:use clojure.contrib.test-is clojure.contrib.monads clojure.contrib.error-kit
        [clojure.contrib.except :only [throw-arg]])
  (:require [name.choi.joshua.fnparse :as p]))

(defstruct state-s :remainder :index :column)
(def make-state (partial struct state-s))
(deferror parse-error [] []
  {:msg "WHEEE", :unhandled (throw-msg IllegalArgumentException)})
(deferror weird-error [] []
  {:msg "BOOM", :unhandled (throw-msg Exception)})

(deftest emptiness
  (is (= (p/emptiness (make-state '(A B C) 3))
         [nil (make-state '(A B C) 3)])
      "emptiness rule matches emptiness"))

(deftest anything
  (is (= (p/anything (make-state '(A B C) 3))
         ['A (make-state '(B C) 4)])
    "anything rule matches first token")
  (is (= (p/anything (make-state '(A B C)))
         ['A (make-state '(B C))])
    "anything rule matches first token without index")
  (is (nil? (p/anything (make-state nil 0)))
    "anything rule fails with no tokens left")
  (is (= ((p/rep* p/anything) (make-state '(A B C) 0))
         ['(A B C) (make-state nil 3)])
    "repeated anything rule does not create infinite loop"))

(deftest term
  (let [rule (p/term (partial = 'A))]
    (is (= (rule (make-state '[A B] 0)) ['A (make-state '[B] 1)])
      "created terminal rule works when first token fulfills validator")
    (is (nil? (rule (make-state '[B B] 0)))
      "created terminal rule fails when first token fails validator")
    (is (= (rule (make-state '[A] 0)) ['A (make-state nil 1)])
      "created terminal rule works when no remainder")))

(deftest lit
  (is (= ((p/lit 'A) (make-state '[A B] 1))
         ['A (make-state '[B] 2)])
      "created literal rule works when literal token present")
  (is (nil? ((p/lit 'A) (make-state '[B] 1)))
      "created literal rule fails when literal token not present"))

(deftest re-term
  (is (= ((p/re-term #"\s*true\s*") (make-state ["  true" "THEN"] 1))
         ["  true" (make-state ["THEN"] 2)])
      "created re-term rule works when first token matches regex")
  (is (nil? ((p/re-term #"\s*true\s*") (make-state ["false" "THEN"] 1)))
      "created re-term rule fails when first token does not match regex")
  (is (nil? ((p/re-term #"\s*true\s*") (make-state nil 1)))
      "created re-term rule fails when no tokens are left"))

(deftest followed-by
  (is (= ((p/followed-by (p/lit 'A)) (make-state '[A B C] 0))
         ['A (make-state '[A B C] 0)]))
  (is (nil? ((p/followed-by (p/lit 'A)) (make-state '[B C] 0)))))

(deftest not-followed-by
  (is (= ((p/not-followed-by (p/lit 'A)) (make-state '[B C] 0))
         [true (make-state '[B C] 0)]))
  (is (nil? ((p/not-followed-by (p/lit 'A)) (make-state '[A B C] 0)))))

(deftest complex
  (let [rule1 (p/complex [a (p/lit 'A)] (str a "!"))
        rule2 (p/complex [a (p/lit 'A), b (p/lit 'B)] (str a "!" b))]
    (is (= (rule1 (make-state '[A B] 1)) ["A!" (make-state '[B] 2)])
      "created complex rule applies semantic hook to valid subresult")
    (is (nil? (rule1 (make-state '[B A] 1)))
      "created complex rule fails when a given subrule fails")
    (is (= (rule2 (make-state '[A B C] 1)) ["A!B" (make-state '[C] 3)])
      "created complex rule succeeds when all subrules fulfilled in order")
    (is (nil? (rule2 (make-state '[A C] 1)))
      "created complex rule fails when one subrule fails")))

(deftest semantics
  (is (= ((p/semantics (p/lit "hi") #(str % "!")) {:remainder ["hi" "THEN"]})
         ["hi!" {:remainder (list "THEN")}])
      "created simple semantic rule applies semantic hook to valid result of given rule"))

(deftest constant-semantics
  (is (= ((p/constant-semantics (p/lit "hi") {:a 1}) {:remainder ["hi" "THEN"]})
         [{:a 1} {:remainder (list "THEN")}])
      "created constant sem rule returns constant value when given subrule does not fail"))

(deftest validate
  (is (= ((p/validate (p/lit "hi") (partial = "hi")) {:remainder ["hi" "THEN"]})
         ["hi" {:remainder (list "THEN")}])
      "created validator rule succeeds when given subrule and validator succeed")
  (is (nil? ((p/validate (p/lit "hi") (partial = "RST")) {:remainder ["RST"]}))
      "created validator rule fails when given subrule fails")
  (is (nil? ((p/validate (p/lit "hi") (partial = "hi")) {:remainder "hi"}))
      "created validator rule fails when given validator fails"))

(deftest get-remainder
  (is (= ((p/complex [remainder p/get-remainder] remainder) {:remainder ["hi" "THEN"]})
         [["hi" "THEN"] {:remainder ["hi" "THEN"]}])))

(deftest remainder-peek
  (is (= (p/remainder-peek {:remainder (seq "ABC")})
         [\A {:remainder (seq "ABC")}])))

(deftest conc
  (is (= ((p/conc (p/lit "hi") (p/lit "THEN")) {:remainder ["hi" "THEN" "bye"]})
         [["hi" "THEN"] {:remainder (list "bye")}])
      "created concatenated rule succeeds when all subrules fulfilled in order")
  (is (nil? ((p/conc (p/lit "hi") (p/lit "THEN")) {:remainder ["hi" "bye" "boom"]}))
      "created concatenated rule fails when one subrule fails"))

(deftest alt
  (let [literal-true (p/lit "true")
        literal-false (p/lit "false")
        literal-boolean (p/alt literal-true literal-false)]
    (is (= (literal-boolean {:remainder ["false" "THEN"]})
           ["false" {:remainder (list "THEN")}])
        "created alternatives rule works with first valid rule product")
    (is (nil? (literal-boolean {:remainder ["aRSTIR"]}))
        "created alternatives rule fails when no valid rule product present")))

(deftest update-info
  (is (= ((p/update-info :column inc) (make-state [\a] nil 3))
         [3 (make-state [\a] nil 4)])))

(deftest invisi-conc
  (is (= ((p/invisi-conc (p/lit \a) (p/update-info :column inc))
          (make-state "abc" nil 3))
         [\a (make-state (seq "bc") nil 4)])))

(deftest lit-conc-seq
  (is (= ((p/lit-conc-seq "THEN") {:remainder "THEN print 42;"})
         [(vec "THEN") {:remainder (seq " print 42;")}])
      "created literal-sequence rule is based on sequence of given token sequencible")
  (is (= ((p/lit-conc-seq "THEN"
            (fn [lit-token]
              (p/invisi-conc (p/lit lit-token)
                (p/update-info :column inc))))
          {:remainder "THEN print 42;", :column 1})
         [(vec "THEN") {:remainder (seq " print 42;"), :column 5}])
      "created literal-sequence rule uses given rule-maker"))

(deftest lit-alt-seq
  (is (= ((p/lit-alt-seq "ABCD") {:remainder (seq "B 2")})
         [\B {:remainder (seq " 2")}])
      (str "created literal-alternative-sequence rule works when literal symbol present in"
           "sequence"))
  (is (nil? ((p/lit-alt-seq "ABCD") {:remainder (seq "E 2")}))
      (str "created literal-alternative-sequence rule fails when literal symbol not present"
           "in sequence"))
  (is (= ((p/lit-alt-seq "ABCD" (fn [lit-token]
                                    (p/invisi-conc (p/lit lit-token)
                                                   (p/update-info :column inc))))
          {:remainder "B 2", :column 1})
         [\B {:remainder (seq " 2"), :column 2}])
      "created literal-alternative-sequence rule uses given rule-maker"))

(deftest opt
  (let [opt-true (p/opt (p/lit "true"))]
    (is (= (opt-true {:remainder ["true" "THEN"]})
           ["true" {:remainder (list "THEN")}])
        "created option rule works when symbol present")
    (is (= (opt-true {:remainder (list "THEN")})
           [nil {:remainder (list "THEN")}])
        "created option rule works when symbol absent")))

(deftest rep*
  (let [rep*-true (p/rep* (p/lit true))
        rep*-untrue (p/rep* (p/except p/anything (p/lit true)))]
    (is (= (rep*-true {:remainder [true "THEN"], :a 3})
           [[true] {:remainder (list "THEN"), :a 3}])
        "created zero-or-more-repetition rule works when symbol present singularly")
    (is (= (rep*-true {:remainder [true true true "THEN"], :a 3})
           [[true true true] {:remainder (list "THEN"), :a 3}])
        "created zero-or-more-repetition rule works when symbol present multiply")
    (is (= (rep*-true {:remainder ["THEN"], :a 3})
           [nil {:remainder (list "THEN"), :a 3}])
     "created zero-or-more-repetition rule works when symbol absent")
    (is (= (rep*-true {:remainder [true true true]})
           [[true true true] {:remainder nil}])
        "created zero-or-more-repetition rule works with no remainder after symbols")
    (is (= (rep*-true {:remainder nil})
           [nil {:remainder nil}])
        "created zero-or-more-repetition rule works with no remainder")
    (is (= (rep*-untrue {:remainder [false false]})
           [[false false] {:remainder nil}])
        "created zero-or-more-repetition negative rule works consuming up to end")
    (is (= (rep*-untrue {:remainder [false false true]})
           [[false false] {:remainder [true]}])
        "created zero-or-more-repetition negative rule works consuming until exception")
    (is (= (rep*-untrue {:remainder nil})
           [nil {:remainder nil}])
        "created zero-or-more-repetition negative rule works with no remainder")))

(deftest rep+
  (let [rep+-true (p/rep+ (p/lit true))]
    (is (= (rep+-true {:remainder [true "THEN"]})
           [[true] {:remainder (list "THEN")}])
        "created one-or-more-repetition rule works when symbol present singularly")
    (is (= (rep+-true {:remainder [true true true "THEN"]})
           [[true true true] {:remainder (list "THEN")}])
        "created one-or-more-repetition rule works when symbol present multiply")
    (is (nil? (rep+-true {:remainder (list "THEN")}))
        "created one-or-more-repetition rule fails when symbol absent")))

(deftest except
  (let [except-rule (p/except (p/lit-alt-seq "ABC") (p/alt (p/lit \B) (p/lit \C)))]
    (is (= (except-rule {:remainder (seq "ABC"), :a 1}) [\A {:remainder (seq "BC"), :a 1}])
        "created exception rule works when symbol is not one of the syntatic exceptions")
    (is (nil? (except-rule {:remainder (seq "BAC")}))
        "created exception rule fails when symbol is one of the syntactic exceptions")
    (is (nil? (except-rule {:remainder (seq "DAB")}))
        "created exception rule fails when symbol does not fulfill subrule")))

(deftest factor=
  (let [tested-rule-3 (p/factor= 3 (p/lit "A")), tested-rule-0 (p/factor= 0 (p/lit "A"))]
    (is (= (tested-rule-3 {:remainder (list "A" "A" "A" "A" "C")})
           [["A" "A" "A"] {:remainder (list "A" "C")}])
        (str "created factor= rule works when symbol fulfills all subrule multiples and"
             "leaves strict remainder"))
    (is (= (tested-rule-3 {:remainder (list "A" "A" "A" "C")})
           [["A" "A" "A"] {:remainder (list "C")}])
        "created factor= rule works when symbol fulfills all subrule multiples only")
    (is (= (tested-rule-3 {:remainder (list "A" "A" "C")}) nil)
        "created factor= rule fails when symbol does not fulfill all subrule multiples")
    (is (= (tested-rule-3 {:remainder (list "D" "A" "B")}) nil)
        "created factor= rule fails when symbol does not fulfill subrule at all")
    (is (= (tested-rule-0 {:remainder (list "D" "A" "B")})
           [[] {:remainder (list "D" "A" "B")}])
        "created factor= rule works when symbol fulfils no multiples and factor is zero")))

(deftest rep-predicate
  (let [tested-rule-fn (p/rep-predicate (partial > 3) (p/lit "A"))
        infinity-rule (p/rep-predicate (partial > Double/POSITIVE_INFINITY) (p/lit "A"))]
    (is (= (tested-rule-fn {:remainder (list "A" "A" "C")})
           [["A" "A"] {:remainder (list "C")}])
        "created rep rule works when predicate returns true")
    (is (nil? (tested-rule-fn {:remainder (list "A" "A" "A")}))
        "created rep rule fails when predicate returns false")
    (is (= (tested-rule-fn {:remainder (list "D" "A" "B")})
           [nil {:remainder (list "D" "A" "B")}])
        "created rep rule succeeds when symbol does not fulfill subrule at all")))

(deftest rep=
  (let [tested-rule-fn (p/rep= 3 (p/lit \A))]
    (is (= (tested-rule-fn {:remainder (seq "AAAC")})
           [[\A \A \A] {:remainder (seq "C")}])
        "created rep= rule works when symbol only fulfills all subrule multiples")
    (is (nil? (tested-rule-fn {:remainder (seq "AAAA")}))
        "created rep= rule fails when symbol exceeds subrule multiples")
    (is (nil? (tested-rule-fn {:remainder (seq "AAC")}))
        "created rep= rule fails when symbol does not fulfill all subrule multiples")
    (is (nil? (tested-rule-fn {:remainder (seq "DAB")}))
        "created rep= rule fails when symbol does not fulfill subrule at all")))

(deftest factor<
  (let [tested-rule (p/factor< 3 (p/lit \A))]
    (is (= (tested-rule {:remainder (seq "AAAAC")})
           [[\A \A] {:remainder (seq "AAC")}])
        (str "created factor< rule works when symbol fulfills all subrule multiples and"
             "leaves strict remainder"))
    (is (= (tested-rule {:remainder (seq "AAAC")})
           [[\A \A] {:remainder (seq "AC")}])
        "created factor< rule works when symbol fulfills all subrule multiples only")
    (is (= (tested-rule {:remainder (seq "AAC")}) [[\A \A] {:remainder (seq "C")}])
        "created factor< rule works when symbol does not fulfill all subrule multiples")
    (is (= (tested-rule {:remainder (seq "DAB")})
           [nil {:remainder (seq "DAB")}])
        "created factor< rule works when symbol does not fulfill subrule at all")))

(deftest failpoint
  (let [exception-rule (p/failpoint (p/lit "A")
                          (fn [remainder state]
                            (throw-arg "ERROR %s at line %s"
                              (first remainder) (:line state))))]
    (is (= (exception-rule {:remainder ["A"], :line 3}) ["A" {:remainder nil, :line 3}])
        "failing rules succeed when their subrules are fulfilled")
    (is (thrown-with-msg? IllegalArgumentException #"ERROR B at line 3"
          (exception-rule {:remainder ["B"], :line 3})
        "failing rules fail with given exceptions when their subrules fail"))))

(deftest intercept
  (let [parse-error-rule (p/semantics (p/lit \A) (fn [_] (throw (Exception.))))
        intercept-rule (p/intercept parse-error-rule
                         (fn [rule-call] (try (rule-call) (catch Exception e :error))))]
    (is (= (intercept-rule (make-state "ABC")) :error))))

(deftest effects
  (let [rule (p/complex [subproduct (p/lit "A")
                         line-number (p/get-info :line)
                         effects (p/effects (println "!" subproduct)
                                            (println "YES" line-number))]
               subproduct)]
    (is (= (with-out-str
             (is (= (rule {:remainder ["A" "B"], :line 3})
                    ["A" {:remainder (list "B"), :line 3}])
                 "pre-effect rules succeed when their subrules are fulfilled"))
           "! A\nYES 3\n")
        "effect rule should call their effect and return the same state")))

(deftest remainder-bindings
  (binding [p/*remainder-accessor* identity
            p/*remainder-setter* #(identity %2)]
    (is (= ((p/lit \a) "abc") [\a (seq "bc")]))))

(deftest match-rule
  (let [rule (p/lit "A")
        matcher (partial p/match-rule rule identity vector)]
    (is (= (matcher (make-state ["A"])) "A"))
    (is (= (matcher (make-state ["B"])) (make-state ["B"])))
    (is (= (matcher (make-state ["A" "B"]))
           ["A" (make-state ["B"]) (make-state ["A" "B"])]))))

(deftest add-states
  (let [state-a {:remainder '[a b c d], :index 4}
        state-b {:remainder nil, :index 2}]
    (is (= (p/add-states state-a state-b) {:remainder '[c d], :index 6}))))

(deftest find-mem-result
  (let [remainder-1 '[a b c d]
        remainder-2 '[d e f]
        remainder-3 '[a c b d]
        memory {'[a b] 'dummy-1
                '[d e f] 'dummy-2}]
    (is (= (p/find-mem-result memory remainder-1) 'dummy-1))
    (is (= (p/find-mem-result memory remainder-2) 'dummy-2))
    (is (= (p/find-mem-result memory remainder-3) nil))))

(deftest mem
  (let [rule (mem (alt (conc (lit 'a) (lit 'b)) (lit 'c)))]
    (is (= (rule (make-state '[a b c] 2)) ['a (make-state '[b c] 3)]))))

(time (run-tests))
