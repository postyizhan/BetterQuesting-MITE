package bq_standard.integration.nei;

import java.util.Collections;
import java.util.List;

import codechicken.nei.PositionedStack;

public class CustomPositionedStack extends PositionedStack {

    private final List<String> tooltips;

    public CustomPositionedStack(Object object, int x, int y, List<String> tooltips) {
        super(object, x, y);
        this.tooltips = tooltips;
    }

    public CustomPositionedStack(Object object, int x, int y, String tooltip) {
        this(object, x, y, Collections.singletonList(tooltip));
    }

    public List<String> getTooltips() {
        return tooltips;
    }
}
