/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.ui.toolbar;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.github.adamantcheese.chan.controller.NavigationController;
import com.github.adamantcheese.chan.ui.controller.FloatingListMenuController;
import com.github.adamantcheese.chan.ui.view.floating_menu.FloatingListMenu;
import com.github.adamantcheese.chan.utils.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import kotlin.Unit;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getRes;
import static com.github.adamantcheese.chan.utils.AndroidUtils.removeFromParentView;

/**
 * An item for the Toolbar menu. These are ImageViews with an icon, that when pressed call
 * some callback. Add them with the NavigationItem MenuBuilder.
 */
public class ToolbarMenuItem {
    private static final String TAG = "ToolbarMenuItem";

    public int id;
    public boolean visible = true;
    public boolean enabled = true;
    public Drawable drawable;
    public final List<ToolbarMenuSubItem> subItems = new ArrayList<>();
    private ClickCallback clicked;

    @Nullable
    private ToobarThreedotMenuCallback threedotMenuCallback;
    @Nullable
    private NavigationController navigationController;

    // Views, only non-null if attached to ToolbarMenuView.
    private ImageView view;

    public ToolbarMenuItem(
            int id,
            int drawable,
            ClickCallback clicked
    ) {
        this(id, ResourcesCompat.getDrawable(getRes(), drawable, null), clicked);
    }

    public ToolbarMenuItem(
            int id,
            Drawable drawable,
            ClickCallback clicked
    ) {
        this.id = id;
        this.drawable = drawable;
        this.clicked = clicked;
    }

    public ToolbarMenuItem(
            int id,
            int drawable,
            ClickCallback clicked,
            @NonNull NavigationController navigationController,
            @Nullable ToobarThreedotMenuCallback threedotMenuCallback
    ) {
        this.id = id;
        this.drawable = ResourcesCompat.getDrawable(getRes(), drawable, null);
        this.clicked = clicked;
        this.navigationController = navigationController;
        this.threedotMenuCallback = threedotMenuCallback;
    }

    public void attach(ImageView view) {
        this.view = view;
    }

    public void detach() {
        if (view == null) {
            Logger.d(TAG, "Already detached");
            return;
        }

        removeFromParentView(this.view);
        this.view = null;
    }

    public ImageView getView() {
        return view;
    }

    public void addSubItem(ToolbarMenuSubItem subItem) {
        subItems.add(subItem);
    }

    public void removeSubItem(ToolbarMenuSubItem subItem) {
        subItems.remove(subItem);
    }

    public void setVisible(boolean visible) {
        this.visible = visible;

        if (view != null) {
            view.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        if (view != null) {
            if (!enabled) {
                view.setClickable(false);
                view.setFocusable(false);

                DrawableCompat.setTint(view.getDrawable(), Color.GRAY);
            } else {
                view.setClickable(true);
                view.setFocusable(true);

                DrawableCompat.setTint(view.getDrawable(), Color.WHITE);
            }
        }
    }

    public void setImage(int drawable) {
        setImage(ResourcesCompat.getDrawable(getRes(), drawable, null));
    }

    public void setImage(Drawable drawable) {
        setImage(drawable, false);
    }

    public void setImage(Drawable drawable, boolean animated) {
        if (view == null) {
            this.drawable = drawable;
            return;
        }

        if (!animated) {
            view.setImageDrawable(drawable);
        } else {
            TransitionDrawable transitionDrawable =
                    new TransitionDrawable(new Drawable[]{this.drawable.mutate(), drawable.mutate()});

            view.setImageDrawable(transitionDrawable);

            transitionDrawable.setCrossFadeEnabled(true);
            transitionDrawable.startTransition(100);
        }

        this.drawable = drawable;
    }

    public void showSubmenu() {
        if (view == null) {
            Logger.w(TAG, "Item not attached, can't show submenu");
            return;
        }

        if (navigationController == null) {
            Logger.w(TAG, "Attempt to use submenu without navigation controller");
            return;
        }

        List<FloatingListMenu.FloatingListMenuItem> floatingListMenuItems = new ArrayList<>();
        List<ToolbarMenuSubItem> subItems = new ArrayList<>(this.subItems);

        Set<Integer> duplicateIdCheckerSet = new HashSet<>();
        duplicateIdCheckerSet.add(id);

        int duplicateId = checkDuplicateMenuIds(duplicateIdCheckerSet, subItems);
        if (duplicateId != -1) {
            throw new IllegalStateException("Found duplicate menu ids among sub items and their " +
                    "nested items! No duplicates allowed! Duplicate menu id = \"" + duplicateId + "\"");
        }

        for (ToolbarMenuSubItem subItem : subItems) {
            floatingListMenuItems.add(mapToolbarSubItemToFloatingMenuItem(subItem));
        }

        FloatingListMenuController floatingListMenuController = new FloatingListMenuController(
                view.getContext(),
                floatingListMenuItems,
                item -> {
                    ToolbarMenuSubItem subItem = findMenuSubItem(subItems, item.getId());
                    if (subItem != null) {
                        subItem.performClick();
                    }

                    return Unit.INSTANCE;
                },
                () -> {
                    if (threedotMenuCallback != null) {
                        threedotMenuCallback.onMenuHidden();
                    }

                    return Unit.INSTANCE;
                }
        );

        boolean isAlreadyPresenting = navigationController.isAlreadyPresenting(
                controller -> controller instanceof FloatingListMenuController
        );

        if (!isAlreadyPresenting) {
            navigationController.presentController(floatingListMenuController);

            if (threedotMenuCallback != null) {
                threedotMenuCallback.onMenuShown();
            }
        }
    }

    private int checkDuplicateMenuIds(
            Set<Integer> duplicateIdCheckerSet,
            List<ToolbarMenuSubItem> subItems
    ) {
        for (ToolbarMenuSubItem subItem : subItems) {
            if (subItem.moreItems.size() > 0) {
                int returnedId = checkDuplicateMenuIds(duplicateIdCheckerSet, subItem.moreItems);
                if (returnedId >= 0) {
                    return returnedId;
                }
            }

            if (subItem.id < 0) {
                continue;
            }

            if (!duplicateIdCheckerSet.add(subItem.id)) {
                return subItem.id;
            }
        }

        return -1;
    }

    @Nullable
    private ToolbarMenuSubItem findMenuSubItem(List<ToolbarMenuSubItem> subItems, int menuId) {
        for (ToolbarMenuSubItem subItem : subItems) {
            if (subItem.id == menuId) {
                return subItem;
            }

            if (subItem.moreItems.size() > 0) {
                return findMenuSubItem(subItem.moreItems, menuId);
            }
        }

        return null;
    }

    private FloatingListMenu.FloatingListMenuItem mapToolbarSubItemToFloatingMenuItem(ToolbarMenuSubItem subItem) {
        FloatingListMenu.FloatingListMenuItem floatingListMenuItem = new FloatingListMenu.FloatingListMenuItem(
                subItem.id,
                subItem.text,
                null,
                subItem.enabled,
                subItem.isCurrentlySelected
        );

        if (subItem.moreItems.size() > 0) {
            for (ToolbarMenuSubItem nestedItem : subItem.moreItems) {
                floatingListMenuItem.getMore().add(mapToolbarSubItemToFloatingMenuItem(nestedItem));
            }
        }

        return floatingListMenuItem;
    }

    public Object getId() {
        return id;
    }

    public void performClick() {
        if (clicked != null) {
            clicked.clicked(this);
        }
    }

    public void setCallback(ClickCallback callback) {
        clicked = callback;
    }

    public interface ClickCallback {
        void clicked(ToolbarMenuItem item);
    }

    public interface ToobarThreedotMenuCallback {
        void onMenuShown();
        void onMenuHidden();
    }
}
