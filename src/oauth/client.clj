(ns 
    #^{:author "Matt Revelle"
       :doc "OAuth client library for Clojure."} 
  oauth.client
  (:require [oauth.digest :as digest]
            [oauth.signature :as sig]
            [com.twinql.clojure.http :as http]
            [clojure.string :as str]))

(declare success-content
         authorization-header)

(defrecord #^{:doc "OAuth consumer"} consumer
  [key
   secret
   request-uri
   access-uri
   authorize-uri
   signature-method])

(defn check-success-response [m]
  (let [code (:code m)]
    (if (or (< code 200)
              (>= code 300))
      (throw (new Exception (str "Got non-success response " code ".")))
      m)))

(defn success-content [m]
  (:content
     (check-success-response m)))

(defn make-consumer
  "Make a consumer struct map."
  [key secret request-uri access-uri authorize-uri signature-method]
  (consumer.  key
              secret
              request-uri 
              access-uri 
              authorize-uri 
              signature-method))

;;; Parse form-encoded bodies from OAuth responses.
(defmethod http/entity-as :urldecoded
  [entity as status]
  (into {}
        (if-let [body (http/entity-as entity :string status)]
          (map (fn [kv]
                 (let [[k v] (str/split kv #"=")
                       k (or k "")
                       v (or v "")]
                   [(keyword (sig/url-decode k)) (sig/url-decode v)]))
               (str/split body #"&"))
          nil)))

(defn request-token
  "Fetch request token for the consumer."
  [consumer & {:as args}]
  
  (let [extra-parameters (:parameters args)
        query (:query args)
        unsigned-params (merge (sig/oauth-params consumer)
                               extra-parameters)
        signature (sig/sign consumer
                            (sig/base-string "POST" 
                                             (:request-uri consumer)
                                             (merge unsigned-params
                                                    query)))
        params (assoc unsigned-params
                 :oauth_signature signature)]

    (success-content
     (http/post (:request-uri consumer)
                :headers {"Authorization" (authorization-header params)}
                :parameters (http/map->params {:use-expect-continue false})
                :query query
                :as :urldecoded))))

(defn user-approval-uri
  "Builds the URI to the Service Provider where the User will be prompted
to approve the Consumer's access to their account."
  [consumer token & {:as rest}]
  (.toString (http/resolve-uri (:authorize-uri consumer) 
                               (merge rest {:oauth_token token}))))

(defn access-token 
  "Exchange a request token for an access token.
  When provided with two arguments, this function operates as per OAuth 1.0.
  With three arguments, a verifier is used."
  ([consumer request-token]
     (access-token consumer request-token nil))
  ([consumer request-token verifier]
     (let [unsigned-params (if verifier
                             (sig/oauth-params consumer
                                               (:oauth_token request-token)
                                               verifier)
                             (sig/oauth-params consumer
                                               (:oauth_token request-token)))
           signature (sig/sign consumer
                               (sig/base-string "POST"
                                                (:access-uri consumer)
                                                unsigned-params)
                               (:oauth_token_secret request-token))
           params (assoc unsigned-params
                    :oauth_signature signature)]
       (success-content
        (http/post (:access-uri consumer)
                   :headers {"Authorization" (authorization-header params)}
                   :parameters (http/map->params {:use-expect-continue false})
                   :as :urldecoded)))))

(defn xauth-access-token
  "Request an access token with a username and password with xAuth."
  [consumer username password]
  (let [oauth-params (sig/oauth-params consumer)
        post-params {:x_auth_username username
                     :x_auth_password password
                     :x_auth_mode "client_auth"}
        signature (sig/sign consumer
                            (sig/base-string "POST"
                                             (:access-uri consumer)
                                             (merge oauth-params
                                                    post-params)))
        params (assoc oauth-params
                 :oauth_signature signature)]
    (success-content
     (http/post (:access-uri consumer)
                :query post-params
                :headers {"Authorization" (authorization-header params)}
                :parameters (http/map->params {:use-expect-continue false})
                :as :urldecoded))))

(defn credentials
  "Return authorization credentials needed for access to protected resources.  
The key-value pairs returned as a map will need to be added to the 
Authorization HTTP header or added as query parameters to the request."
  ([consumer token token-secret request-method request-uri & [request-params]]
     (let [unsigned-oauth-params (sig/oauth-params consumer token)
           unsigned-params (merge request-params 
                                  unsigned-oauth-params)
           signature (sig/sign consumer 
                               (sig/base-string (-> request-method
                                                    name
                                                    str/upper-case)
                                                request-uri
                                                 unsigned-params)
                               token-secret)]
       (assoc unsigned-oauth-params :oauth_signature signature))))

(defn authorization-header
  "OAuth credentials formatted for the Authorization HTTP header."
  ([oauth-params]
     (str "OAuth " (str/join ", " (map (fn [[k v]] 
                                     (str (-> k name sig/url-encode) "=\"" (-> v str sig/url-encode) "\""))
                                   oauth-params))))
  ([oauth-params realm]
     (authorization-header (assoc oauth-params realm))))

(defn check-success-response [m]
  (let [code (:code m)
        reason (:reason m)]
    (if (or (< code 200)
            (>= code 300))
      (throw (new Exception (str "Got non-success code: " code ". "
                                 "Reason: " reason ", "
                                 "Content: " (:content m))))
      m)))

(defn success-content [m]
  (:content (check-success-response m)))
