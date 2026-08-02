(ns solid-social.core
  (:require [reagent.dom.client :as rdc]
            [solid-social.state :as state]
            [solid-social.views :as views]))

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn- render []
  (rdc/render root [views/app]))

(defn ^:dev/after-load re-render []
  (render))

(defn init []
  (state/init!)
  (render))
