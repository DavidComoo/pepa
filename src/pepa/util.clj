(ns pepa.util
  (:require [clojure.java.io :as io]
            [pepa.log :as log]
            [clojure.string :as s])
  (:import java.io.InputStream
           java.io.ByteArrayOutputStream
           java.lang.ProcessBuilder))

(defprotocol ISlurpBytes
  (slurp-bytes [this]))

(extend-protocol ISlurpBytes
  (Class/forName "[B")
  (slurp-bytes [bytes] bytes)

  String
  (slurp-bytes [str]
    (.getBytes str))

  Object
  (slurp-bytes [in]
    (with-open [out (ByteArrayOutputStream.)]
      (io/copy (io/input-stream in) out)
      (.toByteArray out))))

(defmacro with-temp-file [[name data suffix] & body]
  `(let [data# ~data
         ~name (java.io.File/createTempFile "pepa" (or ~suffix ""))]
     (try
       (when data#
         (io/copy data# ~name))
       ~@body
       (finally
         (.delete ~name)))))

(defn run-process
  ([command args]
   (run-process command args {}))
  ([command args opts]
   (let [{:keys [timeout ex-data collect-output?]} opts
         process (-> (into-array (map str (cons command args)))
                     (ProcessBuilder.)
                     (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                     (.start))]
     (try
       (log/debug "Running process:" command (s/join " " args)
                  (str "timeout: " timeout) ", collect-output?: " collect-output? ")")
       (let [output (when collect-output?
                      (slurp (.getInputStream process)))
             fut (future (.waitFor process))
             exit-code (if (and timeout (number? timeout))
                         ;; TODO: We can use the new (.setTimeout
                         ;; process timeout unit) soon
                         (deref fut timeout ::timeout)
                         @fut)]
         (if (and (not= ::timeout exit-code)
                  (zero? exit-code))
           process
           (throw (ex-info (str (pr-str command) " didn't terminate correctly")
                           (assoc ex-data
                                  :exit-code exit-code
                                  :args args
                                  ::timeout timeout))))
         output)
       (finally
         (.destroy process))))))
