(ns commiteth.handlers
  (:require [commiteth.db :as db]
            [re-frame.core :refer [dispatch
                                   reg-event-db
                                   reg-event-fx
                                   reg-fx
                                   inject-cofx]]
            [ajax.core :refer [GET POST]]
            [cuerdas.core :as str]
            [akiroz.re-frame.storage
             :as rf-storage
             :refer [reg-co-fx!]]))


(rf-storage/reg-co-fx! :commiteth {:fx :store
                                   :cofx :store})

(reg-fx
 :http
 (fn [{:keys [method url on-success on-error finally params]}]
   (method url
           {:headers {"Accept" "application/transit+json"}
            :handler on-success
            :error-handler on-error
            :finally finally
            :params  params})))
(reg-fx
 :delayed-dispatch
 (fn [{:keys [args timeout]}]
   (js/setTimeout #(dispatch args)
                  timeout)))

(reg-fx
 :redirect
 (fn [{:keys [path]}]
   (println "redirecting to" path)
   (set! (.-pathname js/location) path)))


(reg-event-fx
 :initialize-db
 [(inject-cofx :store)]
 (fn [{:keys [db store]} [_]]
   {:db (merge db/default-db store)}))

(reg-event-db
 :assoc-in
 (fn [db [_ path value]]
   (assoc-in db path value)))


(reg-event-db
 :set-active-page
 (fn [db [_ page]]
   (assoc db :page page)))

(reg-event-fx
 :set-flash-message
 (fn [{:keys [db]} [_ type text]]
   (merge  {:db (assoc db :flash-message [type text])}
           (when (= type :success)
             {:delayed-dispatch {:args [:clear-flash-message]
                                 :timeout 2000}}))))

(reg-event-db
 :clear-flash-message
 (fn [db _]
   (dissoc db :flash-message)))


(defn assoc-in-if-not-empty [m path val]
  (if (not (empty? val))
    (assoc-in m path val)
    m))

(defn update-local-storage-tokens [ls login token admin-token]
  (into {}
        (-> ls
            (assoc-in-if-not-empty [:tokens login :gh-token] token)
            (assoc-in-if-not-empty [:tokens login :gh-admin-token] admin-token))))

(defn tokens-from-local-storage [ls login]
  (get-in ls [:tokens login]))

(reg-event-fx
 :set-active-user
 (fn [{:keys [db store]} [_ user token admin-token]]
   {:db         (assoc db :user user)
    :dispatch-n [[:update-tokens (:login user) token admin-token]
                 [:load-user-profile]]}))

(reg-event-fx
 :update-tokens
 [(inject-cofx :store)]
 (fn [{:keys [db store]} [_ login token admin-token]]
   (let [ls-data (update-local-storage-tokens store login token admin-token)]
     (println "update-tokens, ls-data:" ls-data)
     {:db         (merge db ls-data)
      :store      ls-data})))

;; copied from plumbing.core to avoid cljsbuild warnings
(defn dissoc-in
  [m [k & ks]]
  (when m
    (if-let [res (and ks (dissoc-in (get m k) ks))]
      (assoc m k res)
      (let [res (dissoc m k)]
        (when-not (empty? res)
          res)))))

(reg-event-fx
 :sign-out
 (fn [{:keys [db]} [_]]
   {:db (assoc db :user nil)
    :redirect {:path "/logout"}}))


(reg-event-fx
 :load-top-hunters
 (fn [{:keys [db]} [_]]
   {:db   db
    :http {:method     GET
           :url        "/api/top-hunters"
           :on-success #(dispatch [:set-top-hunters %])}}))


(reg-event-db
 :set-top-hunters
 (fn [db [_ top-hunters]]
   (assoc db :top-hunters top-hunters)))

(reg-event-fx
 :load-activity-feed
 (fn [{:keys [db]} [_]]
   {:db   db
    :http {:method     GET
           :url        "/api/activity-feed"
           :on-success #(dispatch [:set-activity-feed %])}}))


(reg-event-db
 :set-activity-feed
 (fn [db [_ activity-feed]]
   (assoc db :activity-feed activity-feed)))

(reg-event-fx
 :load-open-bounties
 (fn [{:keys [db]} [_]]
   {:db   db
    :http {:method     GET
           :url        "/api/open-bounties"
           :on-success #(dispatch [:set-open-bounties %])}}))

(reg-event-db
 :set-open-bounties
 (fn [db [_ issues]]
   (assoc db :open-bounties issues)))


(reg-event-fx
 :load-owner-bounties
 (fn [{:keys [db]} [_]]
   {:db   db
    :http {:method     GET
           :url        "/api/user/bounties"
           :on-success #(dispatch [:set-owner-bounties %])}}))

(reg-event-db
 :set-owner-bounties
 (fn [db [_ issues]]
   (assoc db :owner-bounties issues)))

(reg-event-fx
 :load-user-profile
 (fn [{:keys [db]} [_]]
   {:db   db
    :http {:method     GET
           :url        "/api/user"
           :on-success #(dispatch [:set-user-profile %])
           :on-error #(dispatch [:sign-out])}}))

(reg-event-fx
 :set-user-profile
 (fn [{:keys [db]} [_ user-profile]]
   {:db
    (assoc db :user (:user user-profile))
    :dispatch-n [[:load-user-repos]
                 [:load-owner-bounties]]}))

(reg-event-db
 :clear-repos-loading
 (fn [db [_]]
   (assoc db :repos-loading? false)))

(reg-event-db
 :set-user-repos
 (fn [db [_ repos]]
   (assoc db :repos repos)))


(defn get-admin-token [db]
  (let [login (get-in db [:user :login])]
    (get-in db [:tokens login :gh-admin-token])))

(reg-event-fx
 :load-user-repos
 (fn [{:keys [db]} [_]]
   {:db   (assoc db :repos-loading? true)
    :http {:method     GET
           :url        "/api/user/repositories"
           :params     {:token (get-admin-token db)}
           :on-success #(dispatch [:set-user-repos (:repositories %)])
           :on-error   #(dispatch [:set-flash-message
                                   :error "Failed to load repositories"])
           :finally    #(dispatch [:clear-repos-loading])}}))


(defn update-repo-state [all-repos full-name data]
  (let [[owner repo-name] (js->clj (.split full-name "/"))]
    (update all-repos
            owner
            (fn [repos] (map (fn [repo] (if (= (:name repo) repo-name)
                                        (assoc repo
                                               :busy? (:busy? data)
                                               :enabled (:enabled data))
                                        repo))
                            repos)))))

(reg-event-fx
 :toggle-repo
 [(inject-cofx :store)]
 (fn [{:keys [db store]} [_ repo]]
   {:db   (assoc db :repos (update-repo-state
                            (:repos db)
                            (:full_name repo)
                            {:busy? true
                             :enabled (:enabled repo)}))
    :http {:method     POST
           :url        "/api/user/repository/toggle"
           :on-success #(dispatch [:repo-toggle-success %])
           :on-error #(dispatch [:repo-toggle-error repo %])
           :finally  #(println "finally" %)
           :params   (merge {:token (get-admin-token store)}
                            (select-keys repo [:id
                                               :owner
                                               :owner-avatar-url
                                               :full_name
                                               :name]))}}))


(reg-event-db
 :repo-toggle-success
 (fn [db [_ repo]]
   (assoc db :repos (update-repo-state (:repos db)
                                       (:full_name repo)
                                       {:busy? false
                                        :enabled (:enabled repo)} ))))

(reg-event-fx
 :repo-toggle-error
 (fn [{:keys [db]} [_ repo response]]
   {:db (assoc db :repos (update-repo-state (:repos db)
                                            (:full_name repo)
                                            {:busy? false}))
    :dispatch [:set-flash-message
               :error (if (= 400 (:status response))
                        (:response response)
                        (str "Failed to toggle repo: "
                             (:status-text response)))]}))


(reg-event-fx
 :update-address
 (fn [{:keys [db]} [_]]
   {:db db
    :dispatch [:set-active-page :update-address]}))


(reg-event-fx
 :save-user-address
 (fn [{:keys [db]} [_ user-id address]]
   (prn "save-user-address" user-id address)
   {:db   (assoc db :updating-address true)
    :http {:method     POST
           :url        "/api/user/address"
           :on-success #(do
                          (dispatch [:assoc-in [:user [:address] address]])
                          (dispatch [:set-flash-message
                                     :success
                                     "Address saved"]))
           :on-error   #(dispatch [:set-flash-message
                                   :error
                                   (:response %)])
           :finally    #(dispatch [:clear-updating-address])
           :params     {:user-id user-id :address address}}}))

(reg-event-db
 :clear-updating-address
 (fn [db _]
   (dissoc db :updating-address)))


(reg-event-fx
 :save-payout-hash
 (fn [{:keys [db]} [_ issue-id payout-hash]]
   {:db   db
    :http {:method     POST
           :url        (str/format "/api/user/bounty/%s/payout" issue-id)
           :on-success #(dispatch [:payout-confirmed issue-id])
           :on-error   #(dispatch [:payout-confirm-failed issue-id])
           :params     {:payout-hash payout-hash}}}))


(defn send-transaction-callback
  [issue-id]
  (fn [error payout-hash]
    (when error
      (do
        (dispatch [:set-flash-message
                   :error
                   (str "Error sending transaction: " error)])
        (dispatch [:payout-confirm-failed issue-id])))
    (when payout-hash
      (dispatch [:save-payout-hash issue-id payout-hash]))))



(reg-event-fx
 :confirm-payout
 (fn [{:keys [db]} [_ {issue-id         :issue_id
                      owner-address    :owner_address
                      contract-address :contract_address
                      confirm-hash     :confirm_hash} issue]]
   (let [send-transaction-fn (aget js/web3 "eth" "sendTransaction")
         payload {:from  owner-address
                  :to    contract-address
                  :value 1
                  :data  (str "0x797af627" confirm-hash)}]
     (try
       (apply send-transaction-fn [(clj->js payload)
                                   (send-transaction-callback issue-id)])
       {:db (assoc-in db [:owner-bounties issue-id :confirming?] true)}
       (catch js/Error e
         {:db (assoc-in db [:owner-bounties issue-id :confirm-failed?] true)
          :dispatch-n [[:payout-confirm-failed issue-id]
                       [:set-flash-message
                        :error
                        (str "Failed to send transaction" e)]]})))))

(reg-event-fx
 :payout-confirmed
 (fn [{:keys [db]} [_ issue-id]]
   {:dispatch [:load-owner-bounties]
    :db (-> db
            (dissoc-in [:owner-bounties issue-id :confirming?])
            (assoc-in [:owner-bounties  issue-id :confirmed?] true))}))

(reg-event-db
 :payout-confirm-failed
 (fn [db [_ issue-id]]
   (println "payout-confirm-failed" issue-id)
   (-> db
       (dissoc-in [:owner-bounties issue-id :confirming?])
       (assoc-in [:owner-bounties issue-id :confirm-failed?] true))))
