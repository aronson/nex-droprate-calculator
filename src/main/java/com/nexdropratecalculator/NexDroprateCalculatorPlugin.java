package com.nexdropratecalculator;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.events.ConfigChanged;

@Slf4j
@PluginDescriptor(name = "Nex Droprate Calculator")
public class NexDroprateCalculatorPlugin extends Plugin {
  @Inject private Client client;
  @Inject private ClientToolbar clientToolbar;
  private NexDroprateCalculatorPanel panel;
  private NavigationButton navButton;
  @Inject private OverlayManager overlayManager;
  @Inject private NexDroprateCalculatorOverlay overlay;
  @Inject private NexDroprateCalculatorConfig config;

  private int ownContribution = 0;
  private int totalContribution = 0;
  
  // State tracking
  private boolean fightActive = false;
  private boolean overlayActive = false;
  
  // MVP/Drop tracking
  private boolean isMVP = false;
  private boolean minContribution = false;
  
  // Timers
  private int ticksSinceFightEnd = -1;
  private static final int RESET_DELAY_TICKS = 2;
  private static final int OVERLAY_REMOVE_TICKS = 50;

  @Override
  protected void startUp() throws Exception {
    log.debug("Starting Nex Droprate Calculator Plugin");

    panel = injector.getInstance(NexDroprateCalculatorPanel.class);
    panel.init();

    final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
    navButton = NavigationButton.builder().tooltip("Nex Calculator").icon(icon).panel(panel).build();

    clientToolbar.addNavigation(navButton);
    
    log.debug("Nex Droprate Calculator Plugin started successfully");
  }

  @Override
  protected void shutDown() {
    log.debug("Shutting down Nex Droprate Calculator Plugin");

    panel.deinit();
    clientToolbar.removeNavigation(navButton);
    overlayManager.remove(overlay);
    panel = null;
    navButton = null;
    fightActive = false;
    overlayActive = false;

    log.debug("Nex Droprate Calculator Plugin shut down successfully");
  }

  @Subscribe
  public void onConfigChanged(ConfigChanged event) {
    if (event.getGroup().equals("nex-droprate-calculator") && (event.getKey().equals("showOverlay"))) {
      if (config.showOverlay() && overlayActive) {
        overlayManager.add(overlay);
      } else {
        overlayManager.remove(overlay);
      }
    }
  }

  @Subscribe
  public void onGameTick(GameTick ignoredTick) {
    NPC nex = findNex();
    boolean nexIsPresent = (nex != null);

    if (fightActive) {
      if (!nexIsPresent) {
        // Fight ended
        log.debug("Nex gone, ending fight");
        endFight();
        return;
      }

      // Still fighting
      int players = client.getPlayers().size();

      panel.updateValues(ownContribution, totalContribution, players, isMVP, minContribution, 1);
      overlay.updateValues(ownContribution, totalContribution, players, isMVP, minContribution, 1);

      // Reset tick contribution
      ownContribution = 0;
      totalContribution = 0;

    } else {
      // Fight not active. Check if we are in cooldown/reset phase.
      if (ticksSinceFightEnd >= 0) {
        ticksSinceFightEnd++;

        if (ticksSinceFightEnd == RESET_DELAY_TICKS) {
            log.debug("Dumping results (resetting panel)");
            int players = client.getPlayers().size();
            panel.updateValues(0, 0, players, isMVP, minContribution, 0);
            overlay.updateValues(0, 0, players, isMVP, minContribution, 0);
        }

        if (ticksSinceFightEnd >= OVERLAY_REMOVE_TICKS) {
            log.debug("Removing overlay after cooldown");
            setOverlayActive(false);
            panel.updateValues(0, 0, 0, false, false, -1);
            overlay.updateValues(0, 0, 0, false, false, -1);
            ticksSinceFightEnd = -1; // Stop timer
        }
      }
    }
  }

  private void startFight() {
    log.debug("Fight started via hitsplat latch");
    fightActive = true;
    ticksSinceFightEnd = -1; // Cancel any cooldown
    
    // Init values
    isMVP = false;
    minContribution = false;
    ownContribution = 0;
    totalContribution = 0;
    
    setOverlayActive(true);
  }

  private void endFight() {
    fightActive = false;
    ticksSinceFightEnd = 0; // Start cooldown

    // Handle end of fight update
    int players = client.getPlayers().size();
    panel.updateValues(0, 0, players, isMVP, minContribution, 0);
    overlay.updateValues(0, 0, players, isMVP, minContribution, 0);
  }
  
  private void setOverlayActive(boolean active) {
      if (active == overlayActive) return;
      overlayActive = active;
      if (active) {
          if (config.showOverlay()) overlayManager.add(overlay);
      } else {
          overlayManager.remove(overlay);
      }
  }

  @Subscribe
  public void onChatMessage(ChatMessage chatMessage) {
    if (chatMessage.getType() == ChatMessageType.GAMEMESSAGE) {
      if (chatMessage.getMessage().contains("You were the MVP for this fight")) {
        log.debug("MVP message detected");
        isMVP = true;
      }
      if (chatMessage.getMessage().contains("received a drop")) {
        log.debug("Drop message detected");
        minContribution = true;
      }
    }
  }

  @Subscribe
  public void onHitsplatApplied(HitsplatApplied hitsplatApplied) {
    NPC nex = findNex();
    if (nex == null) return; // Gate behind Nex presence
    
    Actor actor = hitsplatApplied.getActor();
    
    if (!fightActive) {
      boolean validTrigger = wasNexInteraction(actor, nex);

      if (validTrigger) {
        startFight();
      } else {
        return;
      }
    }
    
    if (fightActive) {
      if (actor instanceof NPC) {
        Hitsplat hitsplat = hitsplatApplied.getHitsplat();
        if (hitsplat.isMine()) {
          log.debug("Hitsplat applied to me: {}", hitsplat.getAmount());
          ownContribution += hitsplat.getAmount();
        }
        if (hitsplat.getHitsplatType() != HitsplatID.HEAL) {
          totalContribution += hitsplat.getAmount();
          log.debug("Total contribution updated: {}", totalContribution);
        }
      }
    }
  }

  // Return if Nex fight is active
  private boolean wasNexInteraction(Actor actor, NPC nex) {
    boolean validTrigger = false;
    // Was the player attacking?
    if (actor instanceof Player) {
      Player player = (Player) actor;
      // Did the player attack Nex?
      Actor interacting = player.getInteracting();
      if (interacting instanceof NPC) {
        NPC npc = (NPC) interacting;
        // If the player attacked Nex, start new fight
        validTrigger = npc.getId() == nex.getId();
      }
    }
    // If the player did not attack Nex, did Nex attack the player?
    if (!validTrigger && actor instanceof NPC) {
      NPC npc = (NPC) actor;
      // Did Nex attack a player?
      Actor interacting = npc.getInteracting();
      if (npc.getId() == nex.getId() && interacting instanceof Player) {
        // Was it this client's player?
        Player player = (Player) interacting;
        // If so, start fight
        validTrigger = player.getId() == client.getLocalPlayer().getId();
      }
    }
    return validTrigger;
  }

  @Provides
  NexDroprateCalculatorConfig provideConfig(ConfigManager configManager) {
    return configManager.getConfig(NexDroprateCalculatorConfig.class);
  }
  
  private NPC findNex() {
    return client.getNpcs().stream()
            .filter(npc -> npc.getId() >= 11278 && npc.getId() <= 11282)
            .findFirst()
            .orElse(null);
  }
}