package com.github.adamantcheese.chan.core.mapper;

import androidx.annotation.Nullable;

import com.github.adamantcheese.chan.core.model.ChanThread;
import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.save.SerializableThread;

import java.util.List;

public class ThreadMapper {

    public static SerializableThread toSerializableThread(List<Post> posts) {
        return new SerializableThread(
                PostMapper.toSerializablePostList(posts)
        );
    }

    @Nullable
    public static ChanThread fromSerializedThread(
            Loadable loadable,
            SerializableThread serializableThread) {
        List<Post> posts = PostMapper.fromSerializedPostList(
                loadable,
                serializableThread.getPostList());

        if (posts.isEmpty()) {
            return null;
        }

        ChanThread chanThread = new ChanThread(
                loadable,
                posts
        );

        chanThread.op = posts.get(0);
        chanThread.archived = true;
        chanThread.closed = true;

        return chanThread;
    }
}
