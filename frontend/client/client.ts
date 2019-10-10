import { BreadboardConfig } from '../core/breadboard.types'
import './client.sass'

const Breadboard = window.Breadboard

async function client () {
  let config: BreadboardConfig
  try {
    config = await Breadboard.loadConfig()
  } catch (err) {
    console.error('Breadboard: Unable to load Breadboard')
    throw err
  }

  try {
    await Breadboard.addScriptFromString(config.clientGraph)
  } catch (err) {
    console.error('Breadboard: Unable to run client-graph.js')
    throw err
  }

  // TODO: Move this to client graph
  await Breadboard.loadVueDependencies({
    useDev: true
  })
  await Breadboard.createDefaultVue(`<v-app>
    <v-layout row>
      <SVGGraph 
        class="w-50 h-screen"
        :player="player" 
        :graph="graph"
        @nodeClick="(node, e) => log('node click', node, e)"
        @edgeClick="(edge, e) => log('edge click', edge, e)"
        @edgeLabelClick="(edge, e) => log('edge label click', edge, e)"
        :nodeBorderWidth="node => node.isEgo ? 2 : 1"
        :nodeFill="node => node.isEgo ? 'blue' : 'lightgrey'"
        :nodeRadius="node => node.data.score * 2 || 30">
        <template v-slot:node-content="{ node }">
          <image v-if="node.isEgo" href="https://i.imgur.com/R8aQfWo.png" width="50" height="50" x="-25" y="-25" /> <!-- external image on ego -->
          <text v-else text-anchor="middle" fill="black">{{node.id}}</text> <!-- centered label inside the node-content slot -->
        </template>
        <template v-slot:edge-label="{ edge }">
          <text text-anchor="middle" font-size="11px">{{edge.target.id === player.id ? edge.source.id : edge.target.id}}</text>  <!-- simple centered edge label -->
        </template>
      </SVGGraph>
      <v-flex class="w-50 h-screen">
        <v-container style="background: #ebebeb" class="h-full">
          <v-layout column>
            <PlayerTimers :player="player"></PlayerTimers>
            <PlayerText :player="player"></PlayerText>
            <PlayerChoices :player="player"></PlayerChoices>
          </v-layout>
        </v-container>
      </v-flex>
    </v-layout>
  </v-app>`)

}

client()
