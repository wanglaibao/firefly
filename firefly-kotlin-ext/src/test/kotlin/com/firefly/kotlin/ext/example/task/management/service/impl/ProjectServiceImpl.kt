package com.firefly.kotlin.ext.example.task.management.service.impl

import com.firefly.annotation.Component
import com.firefly.annotation.Inject
import com.firefly.kotlin.ext.example.task.management.dao.ProjectDao
import com.firefly.kotlin.ext.example.task.management.dao.UserDao
import com.firefly.kotlin.ext.example.task.management.service.ProjectService
import com.firefly.kotlin.ext.example.task.management.vo.ProjectEditor
import com.firefly.kotlin.ext.example.task.management.vo.ProjectResult
import com.firefly.kotlin.ext.example.task.management.vo.Request
import com.firefly.kotlin.ext.example.task.management.vo.Response

/**
 * @author Pengtao Qiu
 */
@Component
class ProjectServiceImpl : ProjectService {

    @Inject
    lateinit var projectDao: ProjectDao
    @Inject
    lateinit var userDao: UserDao

    override suspend fun createProject(request: Request<ProjectEditor>): Response<Long> {
        val projectId = projectDao.insert(request.data.project) ?: throw IllegalStateException("create project exception, the project id is null")
        projectDao.addProjectMembers(projectId, request.data.userIdList)
        return Response(0, "success", projectId)
    }

    override suspend fun getProject(request: Request<Long>): Response<ProjectResult> {
        val project = projectDao.queryById(request.data) ?: return Response(404, "project not found", null)
        val users = projectDao.listProjectMembers(request.data)
        return Response(0, "success", ProjectResult(project, if (users.isEmpty()) listOf() else userDao.listUsers(users)))
    }

}