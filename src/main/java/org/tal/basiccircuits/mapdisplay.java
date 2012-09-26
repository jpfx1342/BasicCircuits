package org.tal.basiccircuits;

import java.util.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.eisental.common.parsing.ParsingUtils;
import org.bukkit.DyeColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.tal.redstonechips.bitset.BitSet7;
import org.tal.redstonechips.bitset.BitSetUtils;
import org.tal.redstonechips.circuit.Circuit;
import org.tal.redstonechips.memory.Memory;
import org.tal.redstonechips.memory.Ram;
import org.tal.redstonechips.memory.RamListener;
import org.tal.redstonechips.wireless.Receiver;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Tal Eisenberg
 */
public class mapdisplay extends Circuit {
    private int mapSizeX = 128;
    private int mapSizeY = 128;
    private int mapSizeXBits = (int)Math.ceil(Math.log(mapSizeX)/Math.log(2));
    private int mapSizeYBits = (int)Math.ceil(Math.log(mapSizeY)/Math.log(2));
    
    private int mapColorMax = 55;//keep color indexes below this to prevent crashes
    private int mapColorBits = (int)Math.ceil(Math.log(mapColorMax)/Math.log(2));
    
    private int xWordlength, yWordlength, colorWordlength;
    
    private Receiver receiver;
    
    private Ram ram;
    private RamListener ramListener;
    
    private int ramPage = 0, ramPageLength;
    
    MapView map;
    MapDisplayRenderer maprend;
    private byte[] buffer;
    private int dirtyX1 = -1, dirtyX2 = -1;
    private int dirtyY1 = -1, dirtyY2 = -1;
    
    @Override
    public void inputChange(int inIdx, boolean state) {
        if (!inputBits.get(0)) return;
        
        if (ram==null) {
            // set pixel
            processPixelInput(inputBits, 1);
        } else {
            // update ram page
            ramPage = BitSetUtils.bitSetToUnsignedInt(inputBits, 1, inputs.length-1);
            if (hasListeners()) debug("Moving to ram page " + ramPage);
            refreshDisplayFromRam();
        }
    }

    class DisplayReceiver extends Receiver {

        @Override
        public void receive(BitSet7 bits) {
            processPixelInput(bits, 0); // set pixel
        }        
    }

    private void processPixelInput(BitSet7 bits, int startIdx) {
        int x = BitSetUtils.bitSetToUnsignedInt(bits, startIdx, xWordlength);
        int y = BitSetUtils.bitSetToUnsignedInt(bits, startIdx+xWordlength, yWordlength);
        int data = BitSetUtils.bitSetToUnsignedInt(bits, startIdx+xWordlength+yWordlength, colorWordlength);

        if (x < mapSizeX && y < mapSizeY) {
            buffer[y*mapSizeX+x] = (byte)(data&0xFF);
            makeDirty(x, y);
        }
        
        if (hasListeners()) debug("Setting (" + x + ", " + y + ") to " + data);        
    }
    
    class DisplayRamListener implements RamListener {
        @Override
        public void dataChanged(Ram ram, BitSet7 address, BitSet7 data) {
            int color = BitSetUtils.bitSetToUnsignedInt(data, 0, mapColorBits);
            int intaddr = BitSetUtils.bitSetToUnsignedInt(address, 0, 32);
            int offset = ramPage * ramPageLength;
            
            if (intaddr >= offset && intaddr < offset + ramPageLength) {
                int idx = intaddr - offset;
                int x = idx % mapSizeX;
                int y = idx / mapSizeX;

                //if (x < mapSizeX && y < mapSizeY)
                    buffer[idx] = (byte)(color&0xFF);
                
                makeDirty(x, y);
                if (hasListeners()) debug("Setting (" + x + ", " + y + ") to " + data);
            }
        }
    }
    
    private void refreshDisplayFromRam() {
        int offset = ramPage * ramPageLength;
        for (int i=offset; i<offset+ramPageLength; i++) {
            int color = BitSetUtils.bitSetToUnsignedInt(ram.read(i), 0, mapColorBits);
            int x = (i-offset) % mapSizeX;
            int y = (i-offset) / mapSizeX;
            
            //if (x < mapSizeX && y < mapSizeY)
                buffer[i] = (byte)(color&0xFF);
            
            if (hasListeners()) debug("Setting (" + x + ", " + y + ") to " + color);
        }
        makeDirty(0, 0);
        makeDirty(mapSizeX+1, mapSizeY+1);
    }
    
    private void makeDirty(int x, int y) {
        if (dirtyX1 < 0 || dirtyX2 < 0 ||
            dirtyY1 < 0 || dirtyY2 < 0 ) {
            dirtyX1 = dirtyX2 = x;
            dirtyY1 = dirtyY2 = y;
        } else {
            if (x < dirtyX1) dirtyX1 = x;
            if (x > dirtyX2) dirtyX2 = x;
            if (y < dirtyY1) dirtyY1 = y;
            if (y > dirtyY2) dirtyY2 = y;
        }
    }
    
    @Override
    protected boolean init(CommandSender sender, String[] args) {
        String channel = null;
        int[] size = null;
        byte[] colorIndex = null;
        
        String[] split = args[0].split("x");
        if (split.length==2 && ParsingUtils.isInt((split[0])) && ParsingUtils.isInt((split[1]))) {
            size = new int[] { Integer.parseInt(split[0]), Integer.parseInt(split[1]) };
        }        
        
        int start = (size==null?0:1);
        if (args.length>start) { // color index
            
            List<Byte> colorList = new ArrayList<Byte>();
            
            for (int i=start; i<args.length; i++) {
                try {
                    colorList.add(DyeColor.valueOf(args[i].toUpperCase()).getData());
                } catch (IllegalArgumentException ie) {
                    // not dye color
                    try {
                        int val = Integer.decode(args[i]);
                        colorList.add((byte)val);
                    } catch (NumberFormatException ne) {
                        if (args[i].startsWith("$")) {
                            try {
                                ram = (Ram)Memory.getMemory(args[i].substring(1), Ram.class);
                            } catch (IllegalArgumentException e) {
                                error(sender, e.getMessage());
                            } catch (IOException e) {
                                error(sender, e.getMessage());
                            }
                        } else if (channel==null) {
                            if (args[i].startsWith("#"))
                                channel = args[i].substring(1);
                            else channel = args[i];
                        } else error(sender, "Invalid argument: " + args[i]);
                    }
                }
            }

            if (!colorList.isEmpty()) {
                colorIndex = new byte[colorList.size()];
                for (int i=0; i<colorList.size(); i++)
                    colorIndex[i] = colorList.get(i);
            }
        }
        
        if (interfaceBlocks.length!=2) {
            error(sender, "Expecting 2 interface blocks. One block in each of 2 opposite corners of the display.");
            return false;
        }
        
        try {
            /*if (size!=null)
                screen = Screen.generateScreen(interfaceBlocks[0].getLocation(), interfaceBlocks[1].getLocation(),
                        size[0], size[1]);
            else 
                screen = Screen.generateScreen(interfaceBlocks[0].getLocation(), interfaceBlocks[1].getLocation());
            */
            //screen.setColorIndex(colorIndex);
            
            if (ram!=null) ramPageLength = mapSizeX * mapSizeY;
            
            info(sender, "Successfully created Map Display. ");
            info(sender, "The screen is " + 
                    mapSizeX + "pixels wide, " + 
                    mapSizeX + "pixels high.");            
            if (ram!=null) info(sender, "Reading pixel data from memory: " + ram.getId());
        } catch (IllegalArgumentException ie) {
            error(sender, ie.getMessage());
            return false;
        }
        
        // expecting 1 clock, enough pins for address width, enough pins for address height, enough pins for color data.
        xWordlength = mapSizeXBits; 
        yWordlength = mapSizeYBits; 
        colorWordlength = mapColorBits;

        if (channel==null && ram==null) {
            int expectedInputs = 1 + xWordlength + yWordlength + colorWordlength;
            if (inputs.length!=expectedInputs && (inputs.length!=0 || channel==null)) {
                error(sender, "Expecting " + expectedInputs + " inputs. 1 clock input, " + xWordlength + " x address input(s)" + (yWordlength!=0?", " + yWordlength + "y address input(s)":"") + 
                        ", and " + colorWordlength + " color data inputs.");
                return false;
            } 

            if (sender instanceof Player) {
                info(sender, "inputs: clock - 0, x: 1-" + xWordlength + (yWordlength!=0?", y: " + (xWordlength+1) + "-" + 
                        (xWordlength+yWordlength):"") + ", color: " + (xWordlength+yWordlength+1) + "-" + 
                        (xWordlength+yWordlength+colorWordlength) + ".");
            }
        } else if (channel!=null) {
            try {
                int len = xWordlength+yWordlength+colorWordlength;
                receiver = new DisplayReceiver();
                receiver.init(sender, channel, len, this);
            } catch (IllegalArgumentException ie) {
                error(sender, ie.getMessage());
                return false;
            }
        } else if (ram!=null) {
            ramListener = new DisplayRamListener();
            ram.addListener(ramListener);
        }

        if (sender!=null) map = redstoneChips.getServer().createMap(redstoneChips.getServer().getWorlds().get(0));
        
        buffer = new byte[mapSizeX * mapSizeY];
        maprend = new MapDisplayRenderer();
        if (map != null) {
            info(sender, "Using mapid "+map.getId());
            maprend.apply(map);
            if (sender instanceof Player) ((Player)sender).sendMap(map);
            if (ram!=null) refreshDisplayFromRam();
        }
        
        return true;
    }
    @Override
    protected void circuitShutdown() {
        if (ram != null)
           ram.getListeners().remove(ramListener);
        if (receiver != null)
           receiver.shutdown();
        if (map != null)
           map.removeRenderer(maprend);
           //I wish there was someway to easily free map ids, but there doesn't seem to be.
    }
    
    @Override
    public void setInternalState(Map<String, String> state) {
        Object mapid = state.get("mapid");

        if (mapid!=null) 
            map = redstoneChips.getServer().getMap((short)(Integer.decode(mapid.toString())&0xFFFF));
        
        if (map == null) {
            redstoneChips.log(Level.SEVERE, "mapdisplay using invalid mapid: "+mapid);
        } else {
            maprend.apply(map);
            redstoneChips.log(Level.INFO, "mapdisplay "+id+" using mapid "+map.getId());
            if (ram!=null) refreshDisplayFromRam();
       }
    }

    @Override
    public Map<String, String> getInternalState() {
        Map<String,String> state = new HashMap<String,String>();
        state.put("mapid", Integer.toString(map.getId()));
        return state;
    }
    
    class MapDisplayRenderer extends MapRenderer {
        public MapDisplayRenderer() {
            super(true);
        }
        
        @Override
        public void render(MapView mv, MapCanvas mc, Player player) {
            if (dirtyX1 >= 0 && dirtyX2 >= 0 &&
                dirtyY1 >= 0 && dirtyY2 >= 0 ) {
                for (int y = dirtyY1; y <= dirtyY2; y++)
                for (int x = dirtyX1; x <= dirtyX2; x++) {
                    if (x < mapSizeX && y < mapSizeY) {
                        byte color = buffer[y*mapSizeX+x];
                        if (color > mapColorMax)
                            color = 0;
                        mc.setPixel(x, y, color);
                    }
                }
                dirtyX1 = dirtyX2 = -1;
                dirtyY1 = dirtyY2 = -1;
            }
        }

        private void apply(MapView map) {
            if (hasListeners()) debug("map has "+map.getRenderers().size()+" renderers");
            for (Iterator<MapRenderer> it = map.getRenderers().iterator(); it.hasNext();) {
                it.next(); it.remove();
            }
            map.addRenderer(this);
        }
    }
}
