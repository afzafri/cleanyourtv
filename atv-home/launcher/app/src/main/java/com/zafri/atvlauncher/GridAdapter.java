package com.zafri.atvlauncher;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class GridAdapter extends RecyclerView.Adapter<GridAdapter.Holder> {
    static final class Holder extends RecyclerView.ViewHolder {
        final Tile tile;

        Holder(Tile tile) {
            super(tile.root);
            this.tile = tile;
        }
    }

    private final Supplier<Tile> factory;
    private final int rowHeight;
    private List<AppInfo> apps = new ArrayList<>();

    GridAdapter(Supplier<Tile> factory, int rowHeight) {
        this.factory = factory;
        this.rowHeight = rowHeight;
    }

    void setApps(List<AppInfo> apps) {
        this.apps = apps;
        notifyDataSetChanged();
    }

    List<AppInfo> apps() {
        return apps;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Tile t = factory.get();
        t.root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        return new Holder(t);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        h.tile.bind(apps.get(position));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}
