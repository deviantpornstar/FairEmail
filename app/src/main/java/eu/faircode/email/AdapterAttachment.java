package eu.faircode.email;

/*
    This file is part of Safe email.

    Safe email is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

public class AdapterAttachment extends RecyclerView.Adapter<AdapterAttachment.ViewHolder> {
    private Context context;
    private LifecycleOwner owner;

    private List<TupleAttachment> all = new ArrayList<>();
    private List<TupleAttachment> filtered = new ArrayList<>();

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        View itemView;
        TextView tvName;
        TextView tvSize;
        ImageView ivStatus;
        ProgressBar progressbar;

        ViewHolder(View itemView) {
            super(itemView);

            this.itemView = itemView;
            tvName = itemView.findViewById(R.id.tvName);
            tvSize = itemView.findViewById(R.id.tvSize);
            ivStatus = itemView.findViewById(R.id.ivStatus);
            progressbar = itemView.findViewById(R.id.progressbar);
        }

        private void wire() {
            itemView.setOnClickListener(this);
        }

        private void unwire() {
            itemView.setOnClickListener(null);
        }

        private void bindTo(TupleAttachment attachment) {
            tvName.setText(attachment.name);

            if (attachment.size != null)
                tvSize.setText(Helper.humanReadableByteCount(attachment.size, false));
            tvSize.setVisibility(attachment.size == null ? View.GONE : View.VISIBLE);

            if (attachment.progress != null)
                progressbar.setProgress(attachment.progress);
            progressbar.setVisibility(
                    attachment.progress == null || attachment.content ? View.GONE : View.VISIBLE);

            if (attachment.content) {
                ivStatus.setImageResource(R.drawable.baseline_visibility_24);
                ivStatus.setVisibility(View.VISIBLE);
            } else {
                if (attachment.progress == null) {
                    ivStatus.setImageResource(R.drawable.baseline_get_app_24);
                    ivStatus.setVisibility(View.VISIBLE);
                } else
                    ivStatus.setVisibility(View.GONE);
            }
        }

        @Override
        public void onClick(View view) {
            int pos = getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION)
                return;
            final TupleAttachment attachment = filtered.get(pos);
            if (attachment != null)
                if (attachment.content) {
                    // Build file name
                    final File dir = new File(context.getCacheDir(), "attachments");
                    final File file = new File(dir, TextUtils.isEmpty(attachment.name)
                            ? "attachment_" + attachment.id
                            : attachment.name.toLowerCase().replaceAll("[^a-zA-Z0-9-.]", "_"));

                    // https://developer.android.com/reference/android/support/v4/content/FileProvider
                    Uri uri = FileProvider.getUriForFile(context, "eu.faircode.email", file);

                    // Build intent
                    final Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    //intent.setType(attachment.type);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Log.i(Helper.TAG, "Sharing " + file + " type=" + attachment.type);

                    // Set permissions
                    List<ResolveInfo> targets = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                    for (ResolveInfo resolveInfo : targets)
                        context.grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    // Check if viewer available
                    if (targets.size() == 0) {
                        Toast.makeText(context, R.string.title_no_viewer, Toast.LENGTH_LONG).show();
                        return;
                    }

                    Bundle args = new Bundle();
                    args.putLong("id", attachment.id);
                    args.putSerializable("file", file);
                    args.putSerializable("dir", dir);

                    // View
                    new SimpleTask<Void>() {
                        @Override
                        protected Void onLoad(Context context, Bundle args) throws Throwable {
                            long id = args.getLong("id");
                            File file = (File) args.getSerializable("file");
                            File dir = (File) args.getSerializable("dir");

                            // Create file
                            if (!file.exists()) {
                                dir.mkdir();
                                file.createNewFile();

                                // Get attachment content
                                byte[] content = DB.getInstance(context).attachment().getContent(id);

                                // Write attachment content to file
                                FileOutputStream fos = null;
                                try {
                                    fos = new FileOutputStream(file);
                                    fos.write(content);
                                } finally {
                                    if (fos != null)
                                        fos.close();
                                }
                            }

                            return null;
                        }

                        @Override
                        protected void onLoaded(Bundle args, Void data) {
                            context.startActivity(intent);
                        }

                        @Override
                        protected void onException(Bundle args, Throwable ex) {
                            Toast.makeText(context, ex.toString(), Toast.LENGTH_LONG).show();
                        }
                    }.load(context, owner, args);
                } else {
                    if (attachment.progress == null) {
                        Bundle args = new Bundle();
                        args.putLong("id", attachment.id);
                        args.putLong("message", attachment.message);
                        args.putInt("sequence", attachment.sequence);

                        new SimpleTask<Void>() {
                            @Override
                            protected Void onLoad(Context context, Bundle args) {
                                long id = args.getLong("id");
                                long message = args.getLong("message");
                                long sequence = args.getInt("sequence");

                                // No need for a transaction
                                DB db = DB.getInstance(context);
                                db.attachment().setProgress(id, 0);

                                EntityMessage msg = db.message().getMessage(message);
                                EntityOperation.queue(db, msg, EntityOperation.ATTACHMENT, sequence);
                                EntityOperation.process(context);

                                return null;
                            }
                        }.load(context, owner, args);
                    }
                }
        }
    }

    AdapterAttachment(Context context, LifecycleOwner owner) {
        this.context = context;
        this.owner = owner;
        setHasStableIds(true);
    }

    public void set(@NonNull List<TupleAttachment> attachments) {
        Log.i(Helper.TAG, "Set attachments=" + attachments.size());

        Collections.sort(attachments, new Comparator<TupleAttachment>() {
            @Override
            public int compare(TupleAttachment a1, TupleAttachment a2) {
                return a1.sequence.compareTo(a2.sequence);
            }
        });

        all.clear();
        all.addAll(attachments);

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new MessageDiffCallback(filtered, all));

        filtered.clear();
        filtered.addAll(all);

        diff.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
                Log.i(Helper.TAG, "Inserted @" + position + " #" + count);
            }

            @Override
            public void onRemoved(int position, int count) {
                Log.i(Helper.TAG, "Removed @" + position + " #" + count);
            }

            @Override
            public void onMoved(int fromPosition, int toPosition) {
                Log.i(Helper.TAG, "Moved " + fromPosition + ">" + toPosition);
            }

            @Override
            public void onChanged(int position, int count, Object payload) {
                Log.i(Helper.TAG, "Changed @" + position + " #" + count);
            }
        });
        diff.dispatchUpdatesTo(AdapterAttachment.this);
    }

    private class MessageDiffCallback extends DiffUtil.Callback {
        private List<TupleAttachment> prev;
        private List<TupleAttachment> next;

        MessageDiffCallback(List<TupleAttachment> prev, List<TupleAttachment> next) {
            this.prev = prev;
            this.next = next;
        }

        @Override
        public int getOldListSize() {
            return prev.size();
        }

        @Override
        public int getNewListSize() {
            return next.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            TupleAttachment a1 = prev.get(oldItemPosition);
            TupleAttachment a2 = next.get(newItemPosition);
            return a1.id.equals(a2.id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            TupleAttachment a1 = prev.get(oldItemPosition);
            TupleAttachment a2 = next.get(newItemPosition);
            return a1.equals(a2);
        }
    }

    @Override
    public long getItemId(int position) {
        return filtered.get(position).id;
    }

    @Override
    public int getItemCount() {
        return filtered.size();
    }

    @Override
    @NonNull
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_attachment, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.unwire();

        TupleAttachment attachment = filtered.get(position);
        holder.bindTo(attachment);

        holder.wire();
    }
}
