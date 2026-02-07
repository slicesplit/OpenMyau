package myau.module.modules.remoteshop;

import myau.module.modules.RemoteShop;
import org.jetbrains.annotations.NotNull;

/**
 * Normal RemoteShop mode
 * Simply caches and reopens any chest GUI
 */
public class NormalRemoteShop extends IRemoteShop {
    
    public NormalRemoteShop(String name, @NotNull RemoteShop parent) {
        super(name, parent);
    }
    
    @Override
    public String getPrettyName() {
        return "Normal";
    }
}
