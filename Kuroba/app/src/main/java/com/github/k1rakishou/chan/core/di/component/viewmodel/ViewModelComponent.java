package com.github.k1rakishou.chan.core.di.component.viewmodel;

import com.github.k1rakishou.chan.core.di.module.viewmodel.ViewModelModule;
import com.github.k1rakishou.chan.core.di.scope.PerViewModel;
import com.github.k1rakishou.chan.features.media_viewer.MediaViewerControllerViewModel;
import com.github.k1rakishou.chan.features.site_archive.BoardArchiveViewModel;

import dagger.Subcomponent;

@PerViewModel
@Subcomponent(modules = ViewModelModule.class)
public abstract class ViewModelComponent {
    public abstract void inject(MediaViewerControllerViewModel mediaViewerControllerViewModel);
    public abstract void inject(BoardArchiveViewModel boardArchiveViewModel);

    @Subcomponent.Builder
    public interface Builder {
        ViewModelComponent build();
    }
}
