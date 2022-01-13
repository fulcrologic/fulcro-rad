(ns com.fulcrologic.rad.type-support.js-date-formatter-spec
  (:require
    [com.fulcrologic.rad.type-support.js-date-formatter :as jdf]
    [fulcro-spec.core :refer [assertions specification behavior =>]]))

(specification "tokenization"
  (assertions
    "Tokenizes complex patterns"
    (jdf/tokenize "E MMM dd, yyyy") => ["E" " " "MMM" " " "dd" "," " " "yyyy"]
    (jdf/tokenize "MM, E MMM yy_EEE-dd, yyyy") => ["MM" "," " " "E" " " "MMM" " " "yy" "_" "EEE" "-" "dd" "," " " "yyyy"]
    "Understands literals"
    (jdf/tokenize "'X' E MMM dd'''' 'T'") => [{:literal "X"} " " "E" " " "MMM" " " "dd" {:literal "'"} {:literal "'"} " " {:literal "T"}]))
