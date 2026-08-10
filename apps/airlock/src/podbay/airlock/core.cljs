(ns podbay.airlock.core
  (:require [podbay.airlock.state :as state]
            [podbay.airlock.views :as views]
            [reagent.dom.client :as rdc]))

(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn- render []
  (rdc/render root [views/app]))

(defn ^:dev/after-load re-render []
  (render))

(defn init []
  (state/init!)
  (render))
